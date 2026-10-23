package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.DataSetObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.AbsListView;
import android.widget.Adapter;
import android.widget.HeaderViewListAdapter;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.users.UserUtils;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Loads a followers / following list to the end through Instagram's own pager and reads every
 * account Instagram returned (Export CSV, Followers history, Profile analyzer).
 *
 * <p>IG 451 (FollowListFragment X.0D96): the fragment's pager (field X.0D96.A0O, class X.0E2b:
 * declares isLoading() and keeps the next max_id in its one String field) has two no-arg void
 * methods. "Load more if idle" (AV7) checks the lifecycle, then skips while the last page failed
 * or is in flight; "fetch the next page" (Ei0) hands the request straight to the fragment's query
 * manager, which drops it when that page is already queued or in flight, waits 200 ms, then sends
 * it with the last max_id (building it clears the failed flag). A page's response sets the pager's
 * next max_id, appends the page's Users to the adapter's account list (X.0D9E.A11) and rebuilds
 * the adapter, which ends in notifyDataSetChanged. A failed page shows Instagram's error toast and
 * sets the failed flag, so "load more" stops asking.
 *
 * <p>Driving: a DataSetObserver on the list's adapter runs a step as soon as a page is applied,
 * and the step asks for the next page right away: no scrolling and no fixed waits between pages
 * (the old driver polled every 500 ms and needed two empty polls to end). A 400 ms tick backs the
 * observer up. A failed page (the pager's isLoading() false while a next max_id is set, i.e. its
 * failed flag; or no page for STALL_MS) is asked for again through "fetch the next page" after
 * growing waits (FollowListPolicy.retryDelayMs), and the list only counts as unfinished when the
 * last retry failed too; the old driver kept polling a failed list until Stop was tapped. The
 * end: the next max_id is null after a page.
 *
 * <p>Accounts: read from the adapter's own account list (its List field holding Users: the one
 * that grows as pages come in, else the largest), not from the ListView's rows, which also hold
 * section headers, the loading row and suggestions, and not from a walk over everything the
 * adapter reaches (that walk also found suggested accounts and stopped after 20,000 objects).
 * Accounts are keyed by id (the User's final numeric String field), so a row Instagram repeats on
 * a later page counts once; the progress shows that same count, which is what the result holds.
 * Instagram's own count for the list (follower_count / following_count of the profile's User,
 * found by their field-name hashes) is passed along so a shorter list can be reported honestly.
 *
 * <p>Pages (diagnostics): every page Instagram applies to the list goes through one handler, the
 * Function0 that uses "fetch_suggested_users_optimized" (451: X.0key.invoke; it holds the list's
 * response handler X.0YMs, whose Fragment field is the list fragment, the page X.0lfb and the
 * order key the page was asked with). Both of Instagram's page paths end there: the GraphQL one
 * (xdt_api__v1__friendships__followers / __following, X.0WVi → X.0YMs.A00 → X.0dqV & co., used for
 * Followers / Following tabs) and the REST one (friendships/…, X.0WVN → X.0drJ, other lists). It is
 * hooked at startup and, only while a load runs, logs one line per page:
 * <ul>
 * <li>should_limit_list_of_followers (the page getter Instagram itself reads to switch the list to
 * its limited mode, X.0lfb.DRy); on Following lists also should_limit_list_of_followings (X.0lfb.DRz)
 * and hidden_following_account_count (X.0lfb.CN8, the accounts its "And N others" footer counts;
 * the Followers pages return false / 0 for both). Each page getter is found by its key's hash.</li>
 * <li>the page's account count (X.0lfb.CqI);</li>
 * <li>where Pando got the page: Summary.source (network, fresh_cache, stale_cache, …) and cacheAge,
 * read from com.facebook.pando.Summary through the page's fields (X.0dqV → X.0WVo → X.00WX →
 * Summary; the path is found once per page class); "src=none" for a page without one (REST);</li>
 * <li>next_max_id from the response model behind the page (X.0fIh, NewFriendListResponseIntf; its
 * Pando model X.0W4w reads each field by its key's hash, which is how its getters are found): the
 * value when it is all digits (12 at most), else "opaque len=N";</li>
 * <li>follow_ranking_token (X.0lfb.CE5) as a hash of it, and whether it is the previous page's
 * (never the token itself);</li>
 * <li>has_more, big_list, page_size and truncate_users_at_index only when they have a value: the
 * query never fetches them (its skip_* variables), so they're left out.</li>
 * </ul>
 * Every getter call is guarded: a field the query didn't fetch shows as "?". A list with either
 * should_limit flag on any page is reported as limited (Result.limited), also when only a page
 * before the load carried it (the hook remembers that per list fragment outside loads; the first
 * page usually comes before). When the flag for this list can't be read at all, a list that ends
 * after 2 pages with at most 60 accounts while Instagram counts 4 times as many counts as limited
 * (FollowListPolicy.limitedByShape). A limited list gets no extra passes.
 *
 * The same line counts the page's accounts that are new in this pass and those an earlier page of
 * the pass already sent (FollowListPolicy.PassTally), with how many pages back the last page that
 * sent each repeat was (1, 2, 3..5, more); each pass ends with its totals (received, distinct,
 * repeated).
 *
 * <p>Extra passes: Instagram's server pages someone else's Followers by position through a ranking
 * made for the viewer that shifts between requests, so a pass repeats some accounts (each repeat
 * takes the place of one it skips: a pass can receive as many rows as the list has, with about a
 * fifth of them repeats) and lists a random ~80% of the list, in any order; its server ignores the sort
 * order on lists that aren't yours (instagrapi #2857). So when the first pass is complete, the list isn't limited, and it repeated
 * accounts or listed clearly fewer than the target (FollowListPolicy.startExtraPasses; target:
 * Instagram's count, or the rows a pass received when more), the same list is listed again and
 * the accounts are merged by id, until the target is reached, a pass adds no account, MAX_PASSES
 * in all, or ROW_BUDGET rows (FollowListPolicy.anotherPass). Each pass after the first logs how
 * many new accounts it added; at the end, how many accounts 1, 2, … passes listed (f1..fk) and
 * Chao1's estimate of those still missing (logged, not a stop rule).
 *
 * On someone else's list each extra pass lists the first pass's own order again (always the
 * default one there: Instagram's UI offers sorting only on your own lists, the sort header row
 * needs the list's owner to be you, and the default order sends no "order" at all). Your own lists
 * keep cycling the other orders, which Instagram honours there: date followed latest, earliest,
 * default (X.0NIL: DEFAULT "", DATE_FOLLOWED_LATEST, DATE_FOLLOWED_EARLIEST; constant names kept),
 * and so do the fallbacks (FollowListPolicy.relistPasses): no page caches, no request hook with a
 * refresh flag for this list type, a page request of a pass sent without the refresh flag (the
 * same order would come from Instagram's 1-day response cache), or a pass in the same order that
 * added less than half the accounts predicted for it (FollowListPolicy.relistFellShort).
 *
 * An order is set through Instagram's own sort handler on the fragment (the void method taking
 * that enum, X.0D96.GEP, which its "Sort by" sheet calls: it returns at once for the order already
 * set, else it sets the order and fills the list in it, X.0D96.A05 "fill_data"). Filling looks the
 * order up in the query manager's page cache first (X.0D96.A0H, X.0YGI: two X.0CMQ, one per mode,
 * default / sorted, each a Map of key → X.0D98 {status, accounts so far, next max_id}; the key is
 * the order string, or the search text in the default order): a cached order shows its cached
 * accounts and goes on from its cached max_id, and the query manager's own first-page request
 * (X.0IgS.A04) skips a cached key too. So before a pass the caches are emptied with their own
 * clear(), which is what Instagram's pull-to-refresh does for the default order (X.0gbN.G2U:
 * X.0YGI.A02.clear(), then the one-shot refresh flag X.0WVi.A00, then the first page); then the
 * list is cleared and the first page fetched from the server, also for the order already set (a
 * different constant is written to the order field first, so GEP doesn't return). Without the
 * caches, no order is listed twice (MAX_PASSES_WITHOUT_REPEATS). Every pass in one order sends the
 * same order key, so a page only counts toward a pass once that pass's first page request was
 * built (a late page of the previous pass would match its key too). When a pass ran in another
 * order, the first pass's order is set back the same way at the end.
 *
 * <p>Response cache (only for the list a load runs on): Instagram's GraphQL builders for these
 * lists (X.0a23.A00 "xdt_api__v1__friendships__followers", .A01 "…__following"; both put
 * search_surface "follow_list_page" into the request_data input) keep pages in a 1-day response
 * cache. A before-hook on the builders (hooked at startup) sets their last argument, the refresh
 * flag pull-to-refresh sets (A00 p9 / A01 p10 = X.0WVi.A00 via X.0fdp.B4y), for requests whose
 * arguments hold the owner's id and whose list type is the load's, so every page of a pass comes
 * from the server. An after-hook logs what the built request (com.facebook.pando.PandoGraphQLRequest)
 * says about the cache: getFreshCacheAgeMs, getMaxToleratedCacheAgeMs, getAdditionalCacheKeyValue
 * and the refresh flag, for the load's requests once per pass and whenever they change, outside
 * loads (any follow list request) only with the module's verbose logging on. The first pass is
 * started over from its first page (caches emptied, order set again), as the pages on screen may
 * be cached ones. The builders always send an empty rank_token (they blank the list's own,
 * FollowListData.A03, while their cache age is above 0); a stable token per pass was tried
 * and Instagram repeated as many accounts per pass, so it isn't sent. The
 * builders' two Integer arguments are no page size: they are the owner's follower_count
 * (following_count on A01) and 03w1's following_count, read by their key hashes and used only as
 * the response cache's extra key ("<a>-<b>", setAdditionalCacheKeyValue).
 *
 * <p>Failed pages: Instagram's failure callback for the list (the method using "UserList
 * onRequestFail" and "fetch_request_fail", 451: X.0fdp.G4U(X.06As result, String key), which sets
 * the pager's failed flag and toasts the error) is hooked at startup and, for the running load's
 * fragment only, sorts the failure (FollowListPolicy.classify): no server answer (X.05tG, an
 * exception) is NETWORK; a server answer (X.05Zw, Instagram's error model X.0CdQ: message,
 * error_type, checkpoint_url, challenge, feedback_message, checkpoint_required, login_required,
 * getStatusCode) is RATE_LIMIT (429, rate_limit_error, "wait a few minutes"), CHALLENGE (checkpoint,
 * challenge, login required), ACTION_BLOCK (feedback_required) or else SERVER. The three blocks end
 * the load at once with Result.blocked, not complete even during an extra pass, and pause loading
 * lists for that account (about 15 min, 24 h, until a list loads fine again; prefs, warned about
 * by FollowListToolsIcon, never retried by itself). NETWORK and SERVER failures go through the
 * retry waits, a SERVER one SERVER_RETRIES times at most.
 */
final class FollowListLoader {

    private static final String TAG = "(IE|FollowExport) ";
    static final String USER = "com.instagram.user.model.User";
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final long TICK_MS = 400;

    // resolved once at install (FollowListExportHook.install → resolve)
    private static Field pagerField, nextMaxIdField;
    private static Method pagerIsLoading, pagerLoadMore, pagerFetch;
    private static Method usernameGetter, fullNameGetter, followerCountGetter, followingCountGetter;
    private static volatile Field userIdField;
    private static volatile Load current;

    // the page handler's fields (the page, its order key, the list's response handler) and the
    // page's own getters for should_limit_list_of_followers, its accounts, should_limit_list_of_followings,
    // hidden_following_account_count and follow_ranking_token
    private static Field pageField, pageKeyField, pageOwnerField;
    private static Method pageLimited, pageUsers, pageLimitedFollowing, pageHiddenFollowing, pageToken;
    private static volatile Field ownerFragField;
    // Pando's Summary of a GraphQL page (source, cacheAge) and, per page class, the fields leading to
    // it (an empty array: the page has none)
    private static Field summarySource, summaryCacheAge;
    private static Class<?> summaryClass;
    private static final Map<Class<?>, Field[]> summaryPaths = new ConcurrentHashMap<>();
    // the built request's cache getters (PandoGraphQLRequest)
    private static Method reqFreshAge, reqMaxAge, reqExtraKey;
    // the list's failure callback: its fragment field, and the result's server answer / exception getters
    private static Field failureFragField;
    private static Method failurePayload, failureError;
    /** Instagram's error model getters (X.0CdQ) by the key they read → method name */
    private static final Map<String, String> errorGetters = new HashMap<>();
    private static Class<?> listFragClass;
    /** List fragments Instagram flagged as limited on a page applied outside a load (a list's first
     *  page usually arrives before the load starts). */
    private static final Map<Object, Boolean> limitedLists = Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** Response fields logged per page, read through the response model's getters. */
    private static final String[] RESPONSE_KEYS = {"should_limit_list_of_followers", "has_more", "big_list",
            "page_size", "truncate_users_at_index", "next_max_id"};
    private static final String[] RESPONSE_TYPES = {"java.lang.Boolean", "java.lang.Boolean", "java.lang.Boolean",
            "java.lang.Integer", "java.lang.Integer", "java.lang.String"};
    private static final int R_LIMIT = 0, R_HAS_MORE = 1, R_BIG_LIST = 2, R_PAGE_SIZE = 3, R_TRUNCATE = 4, R_NEXT = 5;
    /** {response type (interface or model class), its getters by RESPONSE_KEYS (null: not found)} */
    private static final List<Object[]> responseTypes = new ArrayList<>();
    // the fragment's sort order field (X.0NIL enum) and Instagram's sort handler taking it
    private static Field orderField, orderValueField;
    private static Method sortMethod;
    // the fragment's query manager (X.0YGI), its two per-order page caches (X.0CMQ) and their clear()
    private static Field queryManagerField;
    private static Field[] pageCacheFields;
    private static Method pageCacheClear;
    // Instagram's list request builders → {list type, index of the refresh flag argument or -1}
    static final String PANDO_REQUEST = "com.facebook.pando.PandoGraphQLRequest";
    private static final String SURFACE = "follow_list_page";
    private static final Map<Method, Object[]> listBuilders = new HashMap<>();

    private FollowListLoader() {}

    /** What loading a list found: every account by id → {username, full name}, in Instagram's order
     *  (the first pass's, then each extra pass's new ones). */
    static final class Result {
        final Map<String, String[]> users;
        /** The first pass ran out of pages on its own (not stopped, not left, no failed page) and
         *  Instagram didn't block the load (an extra pass ending early keeps it: see interrupted) */
        final boolean complete;
        /** Instagram refused the load (rate limit, challenge, action block) with what it said; null
         *  when it didn't. complete is false then, also during an extra pass. */
        final FollowListPolicy.Block blocked;
        /** an extra pass ended early (stopped, left the list, a failed page): users are the passes so far */
        final boolean interrupted;
        /** rows in Instagram's account list after the first pass (its adapter keeps each account
         *  once, so repeats are rare here), repeated rows, rows without an id */
        final int rows, duplicates, withoutId;
        /** Instagram's count for this list (the number on its tab), -1 when unknown */
        final int expected;
        /** Instagram flagged the list as limited (should_limit_list_of_followers / _followings on one
         *  of its pages; or, when that flag can't be read, the list looks capped) */
        final boolean limited;
        /** hidden_following_account_count: the largest on any page (Following lists; 0 otherwise or unknown) */
        final int hiddenFollowing;
        /** accounts Instagram sent in the first pass, repeats included, and how many of them an earlier
         *  page of that pass had already sent (page hook; -1 when no page of the pass was counted) */
        final int received, repeated;
        /** different accounts listed by all passes (users.size()) */
        final int distinct;
        /** passes run, the first one included */
        final int passes;
        /** accounts the last extra pass added; -1 when no extra pass ran */
        final int lastPassAdded;
        /** accounts listed by exactly one / exactly two of the passes */
        final int f1, f2;
        /** Chao1's estimate of accounts no pass listed (FollowListPolicy.missingEstimate); -1 with one pass */
        final double estMissing;
        /** the list is the viewer's own (its owner is the logged-in account) */
        final boolean own;

        Result(Map<String, String[]> users, boolean complete, FollowListPolicy.Block blocked, boolean interrupted,
               int rows, int duplicates, int withoutId, int expected, boolean limited, int hiddenFollowing,
               int received, int repeated, int passes, int lastPassAdded, int f1, int f2, double estMissing, boolean own) {
            this.users = users;
            this.complete = complete;
            this.blocked = blocked;
            this.interrupted = interrupted;
            this.rows = rows;
            this.duplicates = duplicates;
            this.withoutId = withoutId;
            this.expected = expected;
            this.limited = limited;
            this.hiddenFollowing = hiddenFollowing;
            this.received = received;
            this.repeated = repeated;
            this.distinct = users.size();
            this.passes = passes;
            this.lastPassAdded = lastPassAdded;
            this.f1 = f1;
            this.f2 = f2;
            this.estMissing = estMissing;
            this.own = own;
        }
    }

    // ---- install-time resolution ----

    /** Finds the pager members on the FollowListFragment class and the User getters. */
    static void resolve(DexKitBridge bridge, ClassLoader cl, Class<?> fragClass) {
        try {
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(USER).paramCount(0).returnType("java.lang.String").usingNumbers("full_name".hashCode())))) {
                fullNameGetter = md.getMethodInstance(cl);
                break;
            }
            // username: the getter reading "username" (-265713450) but not "full_name" (the display-name getter)
            Set<String> displayName = new HashSet<>();
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(USER).paramCount(0).returnType("java.lang.String")
                    .usingNumbers("username".hashCode(), "full_name".hashCode()))))
                displayName.add(md.toString());
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(USER).paramCount(0).returnType("java.lang.String").usingNumbers("username".hashCode())))) {
                if (displayName.contains(md.toString())) continue;
                Method m = md.getMethodInstance(cl);
                if (Modifier.isStatic(m.getModifiers())) continue;
                usernameGetter = m;
                break;
            }
            followerCountGetter = countGetter(bridge, cl, "follower_count");
            followingCountGetter = countGetter(bridge, cl, "following_count");
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Loaded lists may miss usernames, names and Instagram's counts: " + t);
        }
        try {
            findPager(bridge, cl, fragClass);
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not load whole lists: the list loader was not found in this Instagram version", t);
        }
        try {
            listFragClass = fragClass;
            hookPages(bridge, cl);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Lists still load, but a list Instagram limits may not be recognised: " + t);
        }
        try {
            findSort(fragClass);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Lists load in one pass only (the list order cannot be changed), so a few accounts may be missing: " + t);
        }
        try {
            findPageCaches(fragClass);
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "page caches: " + t);
        }
        try {
            hookListBuilders(bridge, cl);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Lists still load, but may come partly from Instagram's cache: " + t);
        }
        try {
            hookFailures(bridge, cl, fragClass);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Lists still load, but a block by Instagram while loading may not be recognised: " + t);
        }
        try {
            summaryClass = cl.loadClass("com.facebook.pando.Summary");
            summarySource = summaryClass.getDeclaredField("source");
            summaryCacheAge = summaryClass.getDeclaredField("cacheAge");
            summarySource.setAccessible(true);
            summaryCacheAge.setAccessible(true);
        } catch (Throwable t) {
            summaryClass = null;
            ModuleLog.detail(TAG + "page summary: " + t);
        }
        StringBuilder builders = new StringBuilder();
        for (Map.Entry<Method, Object[]> e : listBuilders.entrySet())
            builders.append(builders.length() > 0 ? "," : "").append(e.getValue()[0]).append('=').append(e.getKey().getName())
                    .append((int) e.getValue()[1] >= 0 ? "+refresh" : "");
        ModuleLog.detail(TAG + "list request hooks: graphql=[" + builders + "] page caches=" + (pageCacheFields != null
                ? pageCacheFields.length + " (" + pageCacheFields[0].getType().getName() + ")" : "none, orders not repeated"));
        StringBuilder response = new StringBuilder();
        for (Object[] t : responseTypes) {
            int n = 0;
            for (Method m : (Method[]) t[1]) if (m != null) n++;
            response.append(response.length() > 0 ? "," : "").append(((Class<?>) t[0]).getName()).append(' ').append(n).append('/').append(RESPONSE_KEYS.length);
        }
        ModuleLog.detail(TAG + "pager=" + (pagerField != null ? pagerField.getType().getName() : null)
                + " isLoading=" + (pagerIsLoading != null) + " loadMore=" + name(pagerLoadMore) + " fetch=" + name(pagerFetch)
                + " username=" + name(usernameGetter) + " fullName=" + name(fullNameGetter)
                + " counts=" + name(followerCountGetter) + "/" + name(followingCountGetter)
                + " pages=" + (pageField != null ? pageField.getDeclaringClass().getName() : null)
                + " limit=" + name(pageLimited) + "/" + name(pageLimitedFollowing) + " hidden=" + name(pageHiddenFollowing)
                + " token=" + name(pageToken) + " users=" + name(pageUsers) + " response=[" + response + "]"
                + " summary=" + (summaryClass != null) + " request getters=" + (reqFreshAge != null) + "/" + (reqMaxAge != null)
                + "/" + (reqExtraKey != null)
                + " failure=" + (failureFragField != null ? failureFragField.getDeclaringClass().getName() : null) + " " + errorGetters
                + " sort=" + name(sortMethod) + "(" + (orderField != null ? orderField.getType().getName() : null) + ")");
    }

    // ---- page diagnostics (install-time) ----

    /** Hooks Instagram's page handler: the no-arg method using "fetch_suggested_users_optimized" and
     *  "ui_render_start" (451: X.0key.invoke), then finds the page's getters (see the class comment). */
    private static void hookPages(DexKitBridge bridge, ClassLoader cl) throws Throwable {
        MethodDataList found = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .usingEqStrings(List.of("fetch_suggested_users_optimized", "ui_render_start")).paramCount(0)));
        if (found.size() != 1) throw new IllegalStateException("page handler: " + found.size() + " matches");
        Method handler = found.get(0).getMethodInstance(cl);
        for (Field f : SafeReflect.fields(handler.getDeclaringClass())) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            if (f.getType().isInterface()) pageField = f;                // the page (X.0lfb)
            else if (f.getType() == String.class) pageKeyField = f;      // its order key
            else pageOwnerField = f;                                     // the list's response handler
        }
        if (pageField == null || pageOwnerField == null) throw new IllegalStateException("page handler fields");
        ClassMatcher page = ClassMatcher.create().addInterface(pageField.getType().getName(), StringMatchType.Equals, false);
        // should_limit_list_of_followers: the page implementations' boolean getter loading its hash
        pageLimited = pageGetter(bridge, MethodMatcher.create().declaredClass(page).paramCount(0).returnType("boolean")
                .usingNumbers(RESPONSE_KEYS[R_LIMIT].hashCode()));
        // the page's accounts: the List getter whose REST implementation returns the response's getItems()
        pageUsers = pageGetter(bridge, MethodMatcher.create().declaredClass(page).paramCount(0).returnType("java.util.List")
                .addInvoke(MethodMatcher.create().name("getItems")));
        // should_limit_list_of_followings and hidden_following_account_count: read by the Following
        // pages only (X.0dqX / X.0dqu; the Followers ones return false / 0), and follow_ranking_token
        pageLimitedFollowing = pageGetter(bridge, MethodMatcher.create().declaredClass(page).paramCount(0).returnType("boolean")
                .usingNumbers("should_limit_list_of_followings".hashCode()));
        pageHiddenFollowing = pageGetter(bridge, MethodMatcher.create().declaredClass(page).paramCount(0).returnType("int")
                .usingNumbers("hidden_following_account_count".hashCode()));
        pageToken = pageGetter(bridge, MethodMatcher.create().declaredClass(page).paramCount(0).returnType("java.lang.String")
                .usingNumbers("follow_ranking_token".hashCode()));
        resolveResponse(bridge, cl);
        XposedBridge.hookMethod(handler, PAGE_HOOK);
    }

    /** The page interface's method that {@code m} finds in its implementations (one name), or null. */
    private static Method pageGetter(DexKitBridge bridge, MethodMatcher m) {
        try {
            String name = null;
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(m))) {
                if (name != null && !name.equals(md.getName())) return null;
                name = md.getName();
            }
            if (name == null) return null;
            Method g = pageField.getType().getMethod(name);
            g.setAccessible(true);
            return g;
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "page getter: " + t);
            return null;
        }
    }

    /** The response models behind a page: classes with no-arg getters loading the hashes of both
     *  should_limit_list_of_followers and truncate_users_at_index (451: X.0W4w), each with its getters
     *  for RESPONSE_KEYS, taken from the interface it implements for all of them when there is one
     *  (X.0fIh, which the REST model X.070C implements too). */
    private static void resolveResponse(DexKitBridge bridge, ClassLoader cl) throws Throwable {
        Set<String> classes = null;
        for (int k : new int[]{R_LIMIT, R_TRUNCATE}) {
            Set<String> c = new HashSet<>();
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .paramCount(0).returnType(RESPONSE_TYPES[k]).usingNumbers(RESPONSE_KEYS[k].hashCode()))))
                c.add(md.getClassName());
            if (classes == null) classes = c;
            else classes.retainAll(c);
        }
        for (String cn : classes) {
            String[] names = new String[RESPONSE_KEYS.length];
            for (int k = 0; k < RESPONSE_KEYS.length; k++)
                for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(cn).paramCount(0).returnType(RESPONSE_TYPES[k]).usingNumbers(RESPONSE_KEYS[k].hashCode())))) {
                    names[k] = md.getName();
                    break;
                }
            Class<?> model = cl.loadClass(cn), type = model;
            for (Class<?> i : model.getInterfaces()) if (declaresAll(i, names)) { type = i; break; }
            Method[] getters = new Method[names.length];
            for (int k = 0; k < names.length; k++) {
                if (names[k] == null) continue;
                try {
                    getters[k] = type.getMethod(names[k]);
                    getters[k].setAccessible(true);
                } catch (NoSuchMethodException ignored) {}
            }
            responseTypes.add(new Object[]{type, getters});
        }
    }

    private static boolean declaresAll(Class<?> type, String[] names) {
        for (String n : names) {
            if (n == null) continue;
            try { type.getMethod(n); } catch (NoSuchMethodException e) { return false; }
        }
        return true;
    }

    /** Before Instagram applies a page: one log line for the load in progress; outside a load only
     *  whether Instagram flagged the list as limited is remembered. */
    private static final XC_MethodHook PAGE_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
            Load l = current;
            try {
                if (l != null) l.page(param.thisObject);
                else rememberLimited(param.thisObject);
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "page: " + t);
            }
        }
    };

    /** A page outside a load that Instagram flagged as limited (either should_limit flag): remembers
     *  its list fragment (the list's response handler's field holding a FollowListFragment). */
    private static void rememberLimited(Object step) throws Exception {
        if (listFragClass == null) return;
        Object page = pageField.get(step);
        if (!Boolean.TRUE.equals(call(pageLimited, page)) && !Boolean.TRUE.equals(call(pageLimitedFollowing, page))) return;
        Object owner = pageOwnerField.get(step);
        if (owner == null) return;
        for (Field f : SafeReflect.fields(owner.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            Object v = f.get(owner);
            if (listFragClass.isInstance(v)) {
                limitedLists.put(v, Boolean.TRUE);
                return;
            }
        }
    }

    /** The page's response model and its getters: the page itself or an object it holds (two
     *  levels at most) of one of the response types; null when there is none. */
    private static Object[] responseOf(Object page) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[]{page, 0});
        while (!q.isEmpty()) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            for (Object[] t : responseTypes) if (((Class<?>) t[0]).isInstance(o)) return new Object[]{o, t[1]};
            if (d >= 2) continue;
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
                for (Field f : SafeReflect.fields(c)) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                    try {
                        f.setAccessible(true);
                        q.add(new Object[]{f.get(o), d + 1});
                    } catch (Throwable ignored) {}
                }
        }
        return null;
    }

    /** {@code getter} on {@code o}; NOT_READ when there is no getter or it threw (a field the
     *  query didn't fetch, on Pando). */
    private static Object call(Method getter, Object o) {
        if (getter == null || o == null) return NOT_READ;
        try {
            return getter.invoke(o);
        } catch (Throwable t) {
            return NOT_READ;
        }
    }

    private static final Object NOT_READ = new Object() {
        @Override public String toString() { return "?"; }
    };

    /** Where Pando got {@code page}: "src=<Summary.source> age=<cacheAge>", "src=none" for a page
     *  without a Summary (REST), "src=?" when it can't be read. */
    private static String source(Object page) {
        if (page == null || summaryClass == null) return "src=?";
        try {
            Field[] path = summaryPaths.computeIfAbsent(page.getClass(), FollowListLoader::summaryPath);
            if (path.length == 0) return "src=none";
            Object o = page;
            for (Field f : path) {
                o = f.get(o);
                if (o == null) return "src=?";
            }
            return "src=" + summarySource.get(o) + " age=" + summaryCacheAge.getLong(o);
        } catch (Throwable t) {
            return "src=?";
        }
    }

    /** The fields from a page of class {@code pageClass} to its Summary: a field whose class has a
     *  field whose class has a Summary field (451: X.0dqV.A00 X.0WVo → .A00 X.00WX → .A00 Summary);
     *  empty when there is none. */
    private static Field[] summaryPath(Class<?> pageClass) {
        for (Field a : instanceFields(pageClass)) {
            if (a.getType().isPrimitive() || a.getType() == String.class) continue;
            for (Field b : instanceFields(a.getType())) {
                if (b.getType().isPrimitive() || b.getType() == String.class) continue;
                for (Field c : instanceFields(b.getType())) {
                    if (c.getType() != summaryClass) continue;
                    try {
                        a.setAccessible(true);
                        b.setAccessible(true);
                        c.setAccessible(true);
                        return new Field[]{a, b, c};
                    } catch (Throwable t) {
                        return new Field[0];
                    }
                }
            }
        }
        return new Field[0];
    }

    /** {@code c}'s own and inherited instance fields. */
    private static List<Field> instanceFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : SafeReflect.fields(k)) if (!Modifier.isStatic(f.getModifiers())) out.add(f);
        return out;
    }

    /** next_max_id as logged: its value when all digits (12 at most), else its length only. */
    private static String cursorShape(Object next) {
        if (next == NOT_READ) return "?";
        if (!(next instanceof String s)) return next == null ? "none" : "?";
        return s.matches("\\d{1,12}") ? s : "opaque len=" + s.length();
    }

    // ---- sort order (install-time) ----

    /** The fragment's sort order field (an enum with DATE_FOLLOWED_LATEST and DATE_FOLLOWED_EARLIEST;
     *  451: X.0D96.A0L, X.0NIL) and Instagram's sort handler: the fragment's only void method taking
     *  that enum (X.0D96.GEP). The enum's String field is what Instagram sends as "order". */
    private static void findSort(Class<?> fragClass) {
        for (Class<?> c = fragClass; c != null && orderField == null; c = c.getSuperclass())
            for (Field f : SafeReflect.fields(c)) {
                if (Modifier.isStatic(f.getModifiers()) || !f.getType().isEnum()
                        || order(f.getType(), FollowListPolicy.ORDER_LATEST) == null
                        || order(f.getType(), FollowListPolicy.ORDER_EARLIEST) == null) continue;
                f.setAccessible(true);
                orderField = f;
                break;
            }
        if (orderField == null) return;
        for (Field f : SafeReflect.fields(orderField.getType()))
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                f.setAccessible(true);
                orderValueField = f;
            }
        Method found = null;
        for (Class<?> c = fragClass; c != null && c != Object.class; c = c.getSuperclass())
            for (Method m : SafeReflect.methods(c)) {
                if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class || m.getParameterCount() != 1
                        || m.getParameterTypes()[0] != orderField.getType()) continue;
                if (found != null) return; // ambiguous: no extra passes
                found = m;
            }
        if (found != null) found.setAccessible(true);
        sortMethod = found;
    }

    /** The order enum's constant named {@code name}, or null. */
    private static Object order(Class<?> type, String name) {
        Object[] all = type.getEnumConstants();
        if (all != null) for (Object o : all) if (((Enum<?>) o).name().equals(name)) return o;
        return null;
    }

    /** What Instagram sends as "order" for {@code order} (its String field), or its name. */
    private static String orderValue(Object order) {
        try {
            if (orderValueField != null && orderValueField.get(order) instanceof String s) return s;
        } catch (Throwable ignored) {}
        return order != null ? ((Enum<?>) order).name() : null;
    }

    // ---- per-order page caches (install-time) ----

    /** The fragment's query manager (451: X.0D96.A0H, X.0YGI): its only field whose class holds a
     *  boolean (the mode) and exactly two fields of one class with clear() and a Map (the page
     *  caches, X.0CMQ: a synchronized Map of key → page state). Ambiguous: none. */
    private static void findPageCaches(Class<?> fragClass) throws Throwable {
        Field found = null;
        List<Field> caches = null;
        for (Class<?> c = fragClass; c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : SafeReflect.fields(c)) {
                if (Modifier.isStatic(f.getModifiers()) || !f.getType().getName().startsWith("X.")) continue;
                boolean mode = false;
                List<Field> fs = new ArrayList<>();
                for (Field g : SafeReflect.fields(f.getType())) {
                    if (Modifier.isStatic(g.getModifiers())) continue;
                    if (g.getType() == boolean.class) mode = true;
                    else if (isPageCache(g.getType())) fs.add(g);
                }
                if (!mode || fs.size() != 2 || fs.get(0).getType() != fs.get(1).getType()) continue;
                if (found != null) return;
                found = f;
                caches = fs;
            }
        if (found == null) return;
        Method clear = caches.get(0).getType().getMethod("clear");
        clear.setAccessible(true);
        found.setAccessible(true);
        for (Field f : caches) f.setAccessible(true);
        queryManagerField = found;
        pageCacheFields = caches.toArray(new Field[0]);
        pageCacheClear = clear;
    }

    private static boolean isPageCache(Class<?> c) {
        if (c.isPrimitive() || c.isInterface() || !c.getName().startsWith("X.")) return false;
        try {
            Method clear = c.getMethod("clear");
            if (clear.getReturnType() != void.class) return false;
        } catch (NoSuchMethodException e) {
            return false;
        }
        for (Field f : SafeReflect.fields(c))
            if (!Modifier.isStatic(f.getModifiers()) && Map.class.isAssignableFrom(f.getType())) return true;
        return false;
    }

    /** Empties the list's page caches with their own clear() (what Instagram's pull-to-refresh does
     *  for the default order), so the next order set is listed from the server. False when they
     *  aren't known or clearing failed. */
    private static boolean clearPageCaches(Object frag) {
        if (queryManagerField == null || pageCacheFields == null) return false;
        try {
            Object qm = queryManagerField.get(frag);
            if (qm == null) return false;
            for (Field f : pageCacheFields) {
                Object cache = f.get(qm);
                if (cache != null) pageCacheClear.invoke(cache);
            }
            return true;
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "clear page caches: " + (t.getCause() != null ? t.getCause() : t));
            return false;
        }
    }

    // ---- list requests (install-time) ----

    /** Hooks Instagram's GraphQL follow list request builders (see the class comment) by their root
     *  field, "follow_list_page" and "rank_token" (451: X.0a23.A00 / A01). */
    private static void hookListBuilders(DexKitBridge bridge, ClassLoader cl) throws Throwable {
        for (String[] k : new String[][]{{FollowListExportHook.FOLLOWERS, "xdt_api__v1__friendships__followers"},
                {FollowListExportHook.FOLLOWING, "xdt_api__v1__friendships__following"}}) {
            MethodDataList found = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .usingEqStrings(List.of(k[1], SURFACE, "rank_token")).returnType(PANDO_REQUEST)));
            if (found.size() != 1) {
                ModuleLog.warn(TAG + (FollowListExportHook.FOLLOWERS.equals(k[0]) ? "Followers" : "Following")
                        + " lists still load, but may come partly from Instagram's cache");
                ModuleLog.detail(TAG + "list requests: " + k[0] + " builder: " + found.size() + " matches");
                continue;
            }
            Method m = found.get(0).getMethodInstance(cl);
            Class<?>[] p = m.getParameterTypes();
            // the last argument is the refresh flag (A00 p9, A01 p10): true skips the response cache
            listBuilders.put(m, new Object[]{k[0], p.length > 0 && p[p.length - 1] == boolean.class ? p.length - 1 : -1});
        }
        // what the built request says about the response cache (diagnostics; native getters, each guarded)
        try {
            Class<?> req = cl.loadClass(PANDO_REQUEST);
            reqFreshAge = req.getMethod("getFreshCacheAgeMs");
            reqMaxAge = req.getMethod("getMaxToleratedCacheAgeMs");
            reqExtraKey = req.getMethod("getAdditionalCacheKeyValue");
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "request cache getters: " + t);
        }
        for (Method m : listBuilders.keySet()) XposedBridge.hookMethod(m, BUILDER_HOOK);
    }

    /** The GraphQL builders: while a load runs on this list, the refresh flag, so the page comes
     *  from the server; afterwards the built request's cache settings are logged (the load's
     *  requests when they change, others only with verbose logging). */
    private static final XC_MethodHook BUILDER_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
            Load l = current;
            if (l == null) return;
            try {
                Object[] b = listBuilders.get(param.method);
                if (b == null || !l.ownsRequest((String) b[0], param.args)) return;
                int refresh = (int) b[1];
                if (refresh >= 0) param.args[refresh] = Boolean.TRUE;
                l.requestBuilt(refresh >= 0);
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "list requests: " + t);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Load l = current;
            try {
                Object[] b = listBuilders.get(param.method);
                if (b == null) return;
                boolean owned = l != null && l.ownsRequest((String) b[0], param.args);
                if (!owned && !ModuleLog.verbose) return;
                int refresh = (int) b[1];
                String s = "refresh=" + (refresh >= 0 ? param.args[refresh] : "?") + ", " + cacheSettings(param.getResult());
                if (owned) l.requestSettings(s);
                else ModuleLog.probe(TAG + "list request " + b[0] + " (not a load's): " + s);
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "list request settings: " + t);
            }
        }
    };

    /** The built request's response cache settings, each getter guarded ("?" when it threw). */
    private static String cacheSettings(Object req) {
        if (req == null) return "no request";
        return "freshCacheAgeMs=" + call(reqFreshAge, req) + ", maxToleratedCacheAgeMs=" + call(reqMaxAge, req)
                + ", extraCacheKey=" + call(reqExtraKey, req);
    }

    // ---- failed pages (install-time) ----

    /** Hooks the list's failure callback (see the class comment): the 2-argument method using
     *  "UserList onRequestFail" and "fetch_request_fail" (451: X.0fdp.G4U), its field holding the
     *  list fragment (X.0fdp.A00), the result's no-arg getters for the server's answer (Object, X.06As.A01)
     *  and the exception (Throwable, .A02), and the error model's getters by the keys they read
     *  (in the classes whose String getter reads checkpoint_url: X.088M Pando, X.088e REST). */
    private static void hookFailures(DexKitBridge bridge, ClassLoader cl, Class<?> fragClass) throws Throwable {
        MethodDataList found = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .usingEqStrings(List.of("UserList onRequestFail", "fetch_request_fail")).paramCount(2)));
        if (found.size() != 1) throw new IllegalStateException(found.size() + " matches");
        Method cb = found.get(0).getMethodInstance(cl);
        Field frag = null;
        for (Field f : instanceFields(cb.getDeclaringClass())) if (f.getType() == fragClass) frag = f;
        Method payload = null, error = null;
        for (Method m : SafeReflect.methods(cb.getParameterTypes()[0])) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) continue;
            if (m.getReturnType() == Object.class) payload = m;
            else if (m.getReturnType() == Throwable.class) error = m;
        }
        if (frag == null || payload == null || error == null) throw new IllegalStateException("fragment " + (frag != null)
                + ", answer " + (payload != null) + ", exception " + (error != null));
        frag.setAccessible(true);
        payload.setAccessible(true);
        error.setAccessible(true);
        Set<String> models = new HashSet<>();
        for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .paramCount(0).returnType("java.lang.String").usingEqStrings("checkpoint_url"))))
            models.add(md.getClassName());
        // {key, return type or null for an object}
        String[][] keys = {{"message", "java.lang.String"}, {"error_type", "java.lang.String"}, {"checkpoint_url", "java.lang.String"},
                {"feedback_message", "java.lang.String"}, {"checkpoint_required", "boolean"}, {"login_required", "boolean"}, {"challenge", null}};
        for (String[] k : keys) {
            // the getter name every model class has for this key (they share the interface X.0CdQ)
            Set<String> names = null;
            for (String model : models) {
                MethodMatcher mm = MethodMatcher.create().declaredClass(model).paramCount(0).usingEqStrings(k[0]);
                if (k[1] != null) mm.returnType(k[1]);
                Set<String> here = new HashSet<>();
                for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(mm))) {
                    String rt = md.getReturnTypeName();
                    if (md.getName().equals("toString")
                            || (k[1] == null && (rt.equals("java.lang.String") || rt.equals("boolean") || rt.equals("void")))) continue;
                    here.add(md.getName());
                }
                if (names == null) names = here;
                else names.retainAll(here);
            }
            if (names != null && names.size() == 1) errorGetters.put(k[0], names.iterator().next());
        }
        failureFragField = frag;
        failurePayload = payload;
        failureError = error;
        XposedBridge.hookMethod(cb, FAILURE_HOOK);
    }

    /** Before Instagram handles a failed page: sorted and handed to the load when it's the load's list. */
    private static final XC_MethodHook FAILURE_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
            Load l = current;
            if (l == null) return;
            try {
                if (failureFragField.get(param.thisObject) != l.frag) return;
                FollowListPolicy.Failed f = failureOf(param.args[0]);
                String key = param.args[1] instanceof String s ? s : null;
                H.post(() -> l.requestFailed(f, key));
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "failure: " + t);
            }
        }
    };

    /** What a failed page's result says: the exception, or the server's answer read through the
     *  error model's getters (each guarded: a field the response didn't carry reads as missing). */
    private static FollowListPolicy.Failed failureOf(Object result) {
        FollowListPolicy.Failed f = new FollowListPolicy.Failed();
        Object answer = result != null ? call(failurePayload, result) : null;
        Object error = result != null ? call(failureError, result) : null;
        if (answer == NOT_READ) answer = null;
        if (error instanceof Throwable t) {
            f.exception = true;
            f.message = t.getClass().getName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
            return f;
        }
        if (answer == null) return f;
        try {
            if (answer.getClass().getMethod("getStatusCode").invoke(answer) instanceof Integer s) f.status = s;
        } catch (Throwable ignored) {}
        f.message = errorString(answer, "message");
        f.errorType = errorString(answer, "error_type");
        f.feedbackMessage = errorString(answer, "feedback_message");
        String url = errorString(answer, "checkpoint_url");
        f.checkpointUrl = url != null && !url.isEmpty();
        f.challenge = errorValue(answer, "challenge") != null;
        f.checkpointRequired = Boolean.TRUE.equals(errorValue(answer, "checkpoint_required"));
        f.loginRequired = Boolean.TRUE.equals(errorValue(answer, "login_required"));
        return f;
    }

    /** The error model's value for {@code key}, or null (no getter, it threw, or nothing there). */
    private static Object errorValue(Object answer, String key) {
        String name = errorGetters.get(key);
        if (name == null) return null;
        try {
            return answer.getClass().getMethod(name).invoke(answer);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String errorString(Object answer, String key) {
        return errorValue(answer, key) instanceof String s ? s : null;
    }

    private static String name(Method m) { return m != null ? m.getName() : null; }

    /** An order key as logged: "default" for the default order's "", "?" when unknown. */
    private static String keyName(String key) { return key == null ? "?" : key.isEmpty() ? "default" : key; }

    /** User's Integer getter for {@code field} (451: A6M follower_count, A6N following_count). */
    private static Method countGetter(DexKitBridge bridge, ClassLoader cl, String field) throws Throwable {
        for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass(USER).paramCount(0).returnType("java.lang.Integer").usingNumbers(field.hashCode())))) {
            Method m = md.getMethodInstance(cl);
            if (!Modifier.isStatic(m.getModifiers())) return m;
        }
        return null;
    }

    /** IG's list pager: the fragment field whose class has isLoading() and one String field (the next
     *  max_id); its "load more if idle" method is the no-arg void one that checks the lifecycle, and
     *  "fetch the next page" is its only other no-arg void method. On 451 isLoading() is
     *  "in flight, or more pages and not failed" (X.0E2b: A02 || (A00 != null && !A01)), and the
     *  follow list never sets the in-flight flag, so isLoading() false while a next max_id is set
     *  means the last page failed. */
    private static void findPager(DexKitBridge bridge, ClassLoader cl, Class<?> fragClass) throws Throwable {
        for (Class<?> c = fragClass; c != null && pagerField == null; c = c.getSuperclass())
            for (Field f : SafeReflect.fields(c)) {
                try {
                    Method il = f.getType().getDeclaredMethod("isLoading");
                    if (il.getReturnType() != boolean.class) continue;
                    Field maxId = null;
                    int strings = 0;
                    for (Field sf : SafeReflect.fields(f.getType()))
                        if (sf.getType() == String.class && !Modifier.isStatic(sf.getModifiers())) { maxId = sf; strings++; }
                    if (strings != 1) continue;
                    maxId.setAccessible(true);
                    f.setAccessible(true);
                    il.setAccessible(true);
                    nextMaxIdField = maxId;
                    pagerIsLoading = il;
                    pagerField = f;
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
        if (pagerField == null) return;
        for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass(pagerField.getType().getName()).paramCount(0).returnType("void")
                .addInvoke(MethodMatcher.create().name("getLifecycle"))))) {
            pagerLoadMore = md.getMethodInstance(cl);
            break;
        }
        if (pagerLoadMore == null) return;
        Method fetch = null;
        int others = 0;
        for (Method m : SafeReflect.methods(pagerField.getType())) {
            if (m.getParameterCount() != 0 || m.getReturnType() != void.class || Modifier.isStatic(m.getModifiers())
                    || m.getDeclaringClass() != pagerField.getType() || m.equals(pagerLoadMore)) continue;
            fetch = m;
            others++;
        }
        if (others == 1) {
            fetch.setAccessible(true);
            pagerFetch = fetch;
        }
    }

    // ---- loading ----

    static boolean isLoading() { return current != null; }

    /** Stops the load in progress; its result is what was loaded so far (not complete). */
    static void stop() {
        Load l = current;
        if (l != null) {
            l.stopped = true;
            l.poke();
        }
    }

    /**
     * Asks IG's pager for page after page until there is no next max_id, then runs {@code done} on
     * the main thread. The pager only works while the list screen is resumed, so a non-cancelable
     * progress dialog keeps the user here; its Stop button ends early. False when a load is
     * already running.
     */
    static boolean loadAll(View root, Object frag, CharSequence title, Consumer<Result> done) {
        if (current != null) return false;
        Object pager = null;
        try { if (pagerField != null) pager = pagerField.get(frag); } catch (Throwable ignored) {}
        FollowListExportHook.ListInfo info = FollowListExportHook.listInfo(frag);
        int expected = expectedCount(frag, info);
        Load l = new Load(root, frag, pager, info, expected, done);
        current = l;
        if (pager == null || pagerLoadMore == null) {
            ModuleLog.error(TAG + "Could not load the list: the list loader was not found in this Instagram version");
            ModuleLog.detail(TAG + "no pager (pager=" + (pager != null) + ", loadMore=" + (pagerLoadMore != null) + ")");
            l.finish(false, "no pager");
            return true;
        }
        l.dlg = progressDialog(root.getContext(), title);
        l.begin();
        return true;
    }

    /** Accounts in the list's account list right now (0 while its first page is still loading). */
    static int accountsShown(View root) {
        try {
            AbsListView lv = FollowListExportHook.findList(root);
            Object ad = adapterOf(lv);
            if (ad == null) return 0;
            int best = 0;
            for (Field f : listFields(ad.getClass())) {
                Object v = f.get(ad);
                if (v instanceof List<?> l && holdsUsers(l)) best = Math.max(best, l.size());
            }
            return best;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---- pause after a block (per account, in Instagram's private prefs) ----

    private static final String PAUSE_PREFS = "instaeclipse_follow_pause";

    private static SharedPreferences pausePrefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PAUSE_PREFS, Context.MODE_PRIVATE);
    }

    /** The prefs key prefix for account {@code self} ("_" when it isn't known). */
    private static String pauseKey(String self) {
        return (self != null ? self : "_") + ".";
    }

    /** Opens the pause prefs so a later read doesn't wait for the file (the follow list screen calls it). */
    static void warmPause(Context ctx) {
        try { pausePrefs(ctx); } catch (Throwable ignored) {}
    }

    /** The block whose pause still runs for account {@code self} (null: not known), or null. */
    static FollowListPolicy.Block pause(Context ctx, String self) {
        try {
            SharedPreferences p = pausePrefs(ctx);
            String k = pauseKey(self);
            FollowListPolicy.Block b = new FollowListPolicy.Block(FollowListPolicy.Failure.valueOf(p.getString(k + "kind", "")),
                    p.getString(k + "message", null), p.getLong(k + "at", 0), p.getLong(k + "until", 0));
            return b.pausing(System.currentTimeMillis()) ? b : null;
        } catch (Throwable t) {
            return null; // no pause saved (valueOf("") throws)
        }
    }

    private static void savePause(Context ctx, String self, FollowListPolicy.Block b) {
        try {
            String k = pauseKey(self);
            pausePrefs(ctx).edit().putString(k + "kind", b.kind.name()).putString(k + "message", b.message)
                    .putLong(k + "at", b.at).putLong(k + "until", b.until).apply();
            ModuleLog.line(TAG + "Instagram blocked list loading (" + b.kind + "), so loading lists pauses for " + (self != null ? self : "this account")
                    + (b.until == Long.MAX_VALUE ? " until a list loads fine again" : " until " + new Date(b.until)));
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "save pause: " + t);
        }
    }

    private static void clearPause(Context ctx, String self) {
        try {
            SharedPreferences p = pausePrefs(ctx);
            String k = pauseKey(self);
            if (!p.contains(k + "kind")) return;
            p.edit().remove(k + "kind").remove(k + "message").remove(k + "at").remove(k + "until").apply();
            ModuleLog.line(TAG + "A list loaded fine, so the pause after Instagram's block ended for " + (self != null ? self : "this account"));
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "clear pause: " + t);
        }
    }

    /** What a block means: what Instagram did, its own words, and until when loading lists pauses.
     *  {@code stopped}: a load just stopped on it (nothing was saved). */
    static String blockText(Context ctx, FollowListPolicy.Block b, boolean stopped) {
        StringBuilder sb = new StringBuilder(I18n.t(ctx, switch (b.kind) {
            case RATE_LIMIT -> R.string.ig_follow_block_rate;
            case ACTION_BLOCK -> R.string.ig_follow_block_action;
            default -> R.string.ig_follow_block_challenge;
        }));
        if (b.message != null) sb.append("\n\n").append(I18n.t(ctx, R.string.ig_follow_block_said, b.message));
        if (stopped) sb.append("\n\n").append(I18n.t(ctx, R.string.ig_follow_block_stopped));
        if (b.until > 0 && b.until != Long.MAX_VALUE)
            sb.append(stopped ? "\n" : "\n\n").append(I18n.t(ctx, R.string.ig_follow_block_wait,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(b.until))));
        return sb.toString();
    }

    /** Shows a block a load just stopped on. */
    static void showBlocked(Context ctx, FollowListPolicy.Block b) {
        try {
            new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_follow_block_title)).message(blockText(ctx, b, true)).show();
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not show why Instagram stopped the list loading", t);
        }
    }

    /** Warns that loading lists pauses after a block; {@code run} runs only when the user picks
     *  "Load anyway" (never by itself). */
    static void warnPaused(Context ctx, FollowListPolicy.Block b, Runnable run) {
        try {
            IeDialog d = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_follow_block_title)).message(blockText(ctx, b, false));
            d.secondary(I18n.t(ctx, R.string.ig_dialog_cancel), () -> {});
            d.primary(I18n.t(ctx, R.string.ig_follow_block_anyway), run);
            d.show();
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not show the pause warning, so the list was not loaded", t);
        }
    }

    private static final class Load implements Runnable {
        final View root;
        final Object frag, pager;
        final FollowListExportHook.ListInfo info;
        final int expected;
        final Consumer<Result> done;
        final long started = SystemClock.uptimeMillis();
        IeDialog dlg;
        AbsListView list;
        Object adapter;
        DataSetObserver observer;
        // the adapter's List fields, their sizes at the last step and how often each grew
        Field[] lists;
        int[] sizes, growth;
        boolean walked;               // the adapter had no account list: fallback walk
        boolean walkCut;              // ... and the walk stopped at its object cap
        final Map<String, String[]> names = new HashMap<>();
        FollowListPolicy.Counts counts = FollowListPolicy.count(Collections.emptyList());
        final Set<String> cursors = new HashSet<>();
        String cursor;
        int lastUnique = -1, pages, retries, retriesTotal;
        long lastProgressAt, lastAskAt, lastFetchAt, retryAt, lastWalkAt;
        volatile boolean stopped;
        boolean finished;
        // pages Instagram delivered to this list (page hook): pages, pages it flagged as limited,
        // accounts, the largest hidden_following_account_count, the last follow_ranking_token
        int pagesSeen, pagesLimited, usersSeen, hiddenFollowing;
        boolean limited;
        String lastToken;
        // passes: 0 is the first pass, then extra passes 1.., each in the first pass's order again
        // (relist) or in FollowListPolicy.passOrder's orders
        int pass, passPages;
        int maxPasses = FollowListPolicy.MAX_PASSES;
        final boolean own;              // the list is the viewer's own
        boolean relist;                 // extra passes list the first pass's order again
        boolean passRelist;             // ... this one does
        int cycled;                     // extra passes in another order so far
        boolean orderChanged;           // a pass ran in another order: the first one is set back at the end
        volatile boolean noRefresh;     // a page request of this load went out without the refresh flag
        Object firstOrder;              // the first pass's order, set back afterwards
        String firstOrderName;          // ... its constant name (FollowListPolicy order names)
        String passOrderName;           // this pass's order (constant name)
        String passKey;                 // what Instagram sends as "order" in this pass
        long passStarted;
        boolean passPageSeen, passMerged;
        boolean fromStart;              // this pass lists the list from its first page (not the first pass going on)
        FollowListPolicy.PassTally tally = new FollowListPolicy.PassTally(); // this pass's pages as Instagram sent them
        FollowListPolicy.Counts firstCounts; // the first pass's rows: the result's rows / repeats / without id
        int firstReceived = -1, firstRepeated = -1; // the first pass's tally (page hook)
        int maxReceived;                // most accounts Instagram sent in one pass (page hook)
        int passesDone, lastAdded = -1;
        boolean interrupted;            // an extra pass ended early
        final Map<String, String[]> merged = new LinkedHashMap<>(); // accounts of the passes so far, in order
        final Map<String, Integer> hits = new HashMap<>(); // account → passes that listed it
        final StringBuilder passLog = new StringBuilder();
        // the pass whose page requests were last logged by the request builder hook, and the last
        // request cache settings logged
        volatile int requestLogged = -1;
        String lastSettings;
        int settingsPass = -1;
        // page requests the builder hooks saw for this list in this pass; a first-pass page that came
        // before any (asked for before the first pass started over: Instagram's query manager drops a
        // first-page request while that order's page is in flight) lists the first pass again, once.
        // In a pass from the first page, only pages after its first request count toward it.
        volatile int passRequests;
        volatile boolean stalePage;
        boolean staleRetried;
        // the last failed page's kind (failure callback), until a page comes; a block ends the load
        FollowListPolicy.Failure lastFailure;
        FollowListPolicy.Block blocked;

        Load(View root, Object frag, Object pager, FollowListExportHook.ListInfo info, int expected, Consumer<Result> done) {
            this.root = root;
            this.frag = frag;
            this.pager = pager;
            this.info = info;
            this.expected = expected;
            this.done = done;
            lastProgressAt = started;
            limited = limitedLists.containsKey(frag); // flagged on a page before this load
            own = info != null && info.owner != null && info.owner.equals(info.self);
        }

        void begin() {
            list = FollowListExportHook.findList(root);
            adapter = adapterOf(list);
            if (adapter != null) {
                lists = listFields(adapter.getClass()).toArray(new Field[0]);
                sizes = new int[lists.length];
                growth = new int[lists.length];
                // pages already on screen don't count as growth: only pages loaded from here on do
                for (int i = 0; i < lists.length; i++) sizes[i] = sizeOf(lists[i], adapter);
            }
            try {
                Adapter a = list != null ? list.getAdapter() : null;
                if (a != null) {
                    observer = new DataSetObserver() {
                        @Override public void onChanged() { poke(); }
                        @Override public void onInvalidated() { poke(); }
                    };
                    a.registerDataSetObserver(observer);
                }
            } catch (Throwable t) {
                observer = null;
                ModuleLog.detail(TAG + "observer: " + t);
            }
            Object order = currentOrder();
            passOrderName = order instanceof Enum<?> o ? o.name() : null;
            passKey = order != null ? orderValue(order) : null;
            if (pageCacheFields == null) maxPasses = FollowListPolicy.MAX_PASSES_WITHOUT_REPEATS;
            ModuleLog.detail(TAG + "load " + label() + ": " + (lists != null ? lists.length : 0) + " list fields, observer="
                    + (observer != null) + ", expected=" + expected + ", limited before load=" + limited
                    + ", order=" + (passOrderName != null ? passOrderName : "?") + (own ? ", your own list" : ""));
            if (!startOver()) poke();
        }

        /**
         * Starts the first pass over from its first page, so all its pages come from the server (the
         * pages on screen may be from Instagram's 1-day response cache): the page caches are emptied
         * and the order on screen is set again through Instagram's sort handler. Only when the
         * requests can skip that cache (GraphQL builders hooked) and the order can be set; false: the
         * first pass goes on from the pages on screen.
         */
        private boolean startOver() {
            Object order = currentOrder();
            String why = info == null || info.type == null ? "not a followers / following list"
                    : listBuilders.isEmpty() ? "no request hooks" : sortMethod == null || order == null
                    ? "no sort handler" : pageCacheFields == null ? "no page caches" : lists == null || lists.length == 0 ? "no account list" : null;
            if (why == null) {
                fromStart = true;
                passStarted = lastProgressAt = SystemClock.uptimeMillis();
                passRequests = 0;
                if (listFromStart(order)) {
                    ModuleLog.detail(TAG + label() + ": first pass listed again from its first page (order "
                            + passOrderName + ", page caches emptied)");
                    poke();
                    return true;
                }
                fromStart = false;
                why = "sort failed";
            }
            ModuleLog.detail(TAG + label() + ": first pass goes on from the pages on screen (" + why
                    + "): those may come from Instagram's response cache");
            return false;
        }

        /** Lists {@code order} from its first page: empties the page caches, then sets the order
         *  through Instagram's sort handler (for the order already set, after writing another
         *  constant to the order field: the handler returns at once for the order it has). */
        private boolean listFromStart(Object order) {
            boolean cleared = clearPageCaches(frag);
            Object before = currentOrder();
            try {
                if (order == before) {
                    if (!cleared) return false; // it would only show its cached pages again
                    Object other = null;
                    for (Object o : orderField.getType().getEnumConstants()) if (o != order) { other = o; break; }
                    if (other == null) return false;
                    orderField.set(frag, other);
                }
                sortMethod.invoke(frag, order);
                return true;
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "sort " + orderValue(order) + ": " + (t.getCause() != null ? t.getCause() : t));
                try { if (currentOrder() != before && before != null) orderField.set(frag, before); } catch (Throwable ignored) {}
                return false;
            }
        }

        /** A request builder built a page request for this list in this pass ({@code refresh}: with
         *  the response cache skipped); logged once per pass. */
        void requestBuilt(boolean refresh) {
            passRequests++;
            if (!refresh) noRefresh = true;
            int p = pass;
            if (requestLogged == p) return;
            requestLogged = p;
            ModuleLog.detail(TAG + (refresh ? "page requests skip Instagram's response cache for " : "no refresh flag on the page requests for ")
                    + label() + " (pass " + (p + 1) + ")");
        }

        /** The cache settings of a page request built for this list (request builder after-hook):
         *  logged for the first request of each pass and whenever they change. */
        synchronized void requestSettings(String s) {
            int p = pass;
            if (p == settingsPass && s.equals(lastSettings)) return;
            lastSettings = s;
            settingsPass = p;
            ModuleLog.detail(TAG + label() + " page request (pass " + (p + 1) + "): " + s);
        }

        /** This list type's page requests go through a hooked builder that can set the refresh flag. */
        private boolean refreshHooked() {
            for (Object[] b : listBuilders.values()) if (info != null && b[0].equals(info.type) && (int) b[1] >= 0) return true;
            return false;
        }

        /**
         * Instagram's failure callback for one of this list's pages ({@code key}: the order key or
         * search text it was asked with). A block (rate limit, challenge, action block) ends the load
         * at once: nothing is retried, the result isn't complete, and loading lists pauses for this
         * account (FollowListPolicy.pauseUntil). Other failures go through the retry waits.
         */
        void requestFailed(FollowListPolicy.Failed f, String key) {
            if (finished) return;
            FollowListPolicy.Failure kind = FollowListPolicy.classify(f);
            ModuleLog.detail(TAG + label() + ": page request failed (pass " + (pass + 1) + ", order " + keyName(key) + "): " + kind
                    + (f.exception ? ", " + f.message : (f.status >= 0 ? ", status " + f.status : "")
                    + (f.errorType != null ? ", error_type " + f.errorType : "")
                    + (f.message != null ? ", message \"" + f.message + "\"" : "")
                    + (f.feedbackMessage != null ? ", feedback_message \"" + f.feedbackMessage + "\"" : "")
                    + (f.checkpointUrl ? ", checkpoint_url set" : "") + (f.challenge ? ", challenge set" : "")
                    + (f.checkpointRequired ? ", checkpoint_required" : "") + (f.loginRequired ? ", login_required" : "")));
            if (!kind.blocks()) {
                lastFailure = kind;
                poke();
                return;
            }
            long now = System.currentTimeMillis();
            blocked = new FollowListPolicy.Block(kind, FollowListPolicy.shownMessage(f.message, f.feedbackMessage), now,
                    FollowListPolicy.pauseUntil(kind, now));
            savePause(root.getContext(), info != null ? info.self : null, blocked);
            finish(false, "blocked by Instagram (" + kind + ")");
        }

        /** A list request builder's call is for this load's list: the builder's list type is this
         *  list's and one of its arguments is the owner's id (user_id). */
        boolean ownsRequest(String type, Object[] args) {
            if (finished || info == null || info.owner == null || !type.equals(info.type)) return false;
            for (Object a : args) if (info.owner.equals(a)) return true;
            return false;
        }

        /** This list's page requests go through a hooked builder (its type's GraphQL one). */
        private boolean hooked() {
            for (Object[] b : listBuilders.values()) if (info != null && b[0].equals(info.type)) return true;
            return false;
        }

        /** Runs a step now (Instagram applied a page, or Stop was tapped). */
        void poke() {
            H.removeCallbacks(this);
            H.post(this);
        }

        @Override public void run() {
            H.removeCallbacks(this);
            if (finished) return;
            long now = SystemClock.uptimeMillis();
            if (stopped) { finish(false, "stopped"); return; }
            if (!root.isAttachedToWindow()) { finish(false, "left the list"); return; }
            if (stalePage) {
                stalePage = false;
                staleRetried = true;
                ModuleLog.detail(TAG + label() + ": a page asked for before the first pass started over came in, listing it again");
                passPageSeen = false;
                passRequests = 0;
                tally = new FollowListPolicy.PassTally();
                passStarted = lastProgressAt = now;
                cursor = null;
                cursors.clear();
                lastUnique = -1;
                if (!listFromStart(currentOrder())) ModuleLog.detail(TAG + label() + ": could not list the first pass again");
            }
            read(now, false);
            String next;
            try {
                next = (String) nextMaxIdField.get(pager);
            } catch (Throwable t) {
                finish(false, "pager: " + t);
                return;
            }
            boolean newPage = !Objects.equals(next, cursor);
            if (newPage || counts.unique != lastUnique) {
                if (newPage && lastUnique >= 0) { pages++; passPages++; }
                if (newPage && next != null && !cursors.add(next)) {
                    // Instagram sent a page it had already sent: it would go round forever
                    endPass(false, "Instagram repeated a page");
                    return;
                }
                cursor = next;
                lastUnique = counts.unique;
                lastProgressAt = now;
                retries = 0;
                retryAt = 0;
                lastFailure = null;
            }
            boolean failed = next != null && failed();
            if (next == null) {
                // a pass from the first page: an empty first page (Instagram's count 0, or a page
                // seen) ends it, else it waits PASS_FIRST_PAGE_MS for that page
                FollowListPolicy.End end = !fromStart ? FollowListPolicy.atEnd(counts.unique, expected, now - started)
                        : FollowListPolicy.passAtEnd(counts.unique, passPageSeen || expected == 0, now - passStarted);
                if (end == FollowListPolicy.End.COMPLETE) { endPass(true, "end"); return; }
                if (end == FollowListPolicy.End.INCOMPLETE) { endPass(false, "no first page"); return; }
            } else if (retryAt == 0 && !failed && (newPage || now - lastAskAt >= FollowListPolicy.REASK_MS)) {
                ask(pagerLoadMore, now);
            }
            // a failed page: Instagram's failed flag (still set a moment after our last retry: that
            // retry failed too), or no page for STALL_MS. Not the first page of a pass from the first
            // page: nothing can ask for it again (passAtEnd gives it PASS_FIRST_PAGE_MS instead).
            boolean failedAgain = failed && now - lastFetchAt >= FollowListPolicy.REASK_MS + 500;
            boolean passFirstPage = fromStart && next == null;
            if (retryAt == 0 && !passFirstPage && (failedAgain || now - lastProgressAt >= FollowListPolicy.STALL_MS)) {
                // a server error gets fewer retries (FollowListPolicy.SERVER_RETRIES)
                FollowListPolicy.Failure cause = lastFailure;
                long wait = FollowListPolicy.retryDelayMs(retries + 1, cause);
                if (wait < 0) {
                    endPass(false, "page failed after " + retries + " retries" + (cause != null ? " (" + cause + ")" : ""));
                    return;
                }
                retries++;
                retriesTotal++;
                retryAt = now + wait;
                ModuleLog.detail(TAG + label() + ": " + (failedAgain ? "page failed" + (cause != null ? " (" + cause + ")" : "")
                        : "no page for " + (now - lastProgressAt) / 1000 + " s") + ", retry " + retries + " in " + wait / 1000 + " s");
            }
            if (retryAt != 0 && now >= retryAt) {
                retryAt = 0;
                lastProgressAt = now; // a fresh STALL_MS window for this attempt
                lastFetchAt = now;
                ask(pagerFetch != null ? pagerFetch : pagerLoadMore, now);
            }
            progress(now);
            H.postDelayed(this, TICK_MS);
        }

        // ---- passes ----

        /**
         * The pass in progress ended ({@code ok}: Instagram's pager ran out of pages). The first pass
         * finishes the load, or, complete, not limited, and with repeats or clearly short of the
         * target, starts the extra passes (FollowListPolicy.startExtraPasses), in the first pass's
         * order again or in the other orders (FollowListPolicy.relistPasses); an extra pass adds its
         * new accounts and starts the next one while the accounts are still short of the target and
         * it added some (at most maxPasses in all, within ROW_BUDGET). A pass in the same order that
         * fell short of its prediction makes the next ones switch order. A failed extra pass ends the
         * passes (its retries already waited for Instagram).
         */
        private void endPass(boolean ok, String why) {
            if (pass == 0) {
                if (!ok || walkCut) { finish(false, why); return; }
                read(SystemClock.uptimeMillis(), true);
                firstCounts = counts;
                for (String id : counts.ids) merged.put(id, nameOf(id));
                countHits(counts.ids);
                passesDone = 1;
                FollowListPolicy.PassTally t = tally;
                if (t.pages > 0) {
                    firstReceived = t.received;
                    firstRepeated = t.repeated;
                    maxReceived = t.received;
                }
                ModuleLog.detail(TAG + label() + ": pass 1 (order " + keyName(passKey) + "): " + tallyText()
                        + "; " + counts.unique + " listed, " + passRequests + " requests");
                // the pass's pages: those the page hook counted toward it, else the pager's
                int passPagesSeen = pageField != null ? t.pages : pages + 1;
                if (!limited && limitFlagMissing() && FollowListPolicy.limitedByShape(true, passPagesSeen, counts.unique, expected)) {
                    limited = true;
                    ModuleLog.detail(TAG + label() + ": no limit flag readable, but " + counts.unique + " of " + expected + " in "
                            + passPagesSeen + " pages looks capped: limited");
                }
                Object first = currentOrder();
                if (sortMethod == null || first == null || walked || !FollowListPolicy.startExtraPasses(true, limited,
                        merged.size(), expected, firstReceived, firstRepeated)) {
                    if (limited && FollowListPolicy.startExtraPasses(true, false, merged.size(), expected, firstReceived, firstRepeated))
                        ModuleLog.detail(TAG + label() + ": limited by Instagram, so no extra passes");
                    finish(true, why);
                    return;
                }
                firstOrder = first;
                firstOrderName = ((Enum<?>) first).name();
                relist = FollowListPolicy.relistPasses(own, pageCacheFields != null, refreshHooked()) && passRequests > 0 && !noRefresh;
                ModuleLog.detail(TAG + label() + ": extra passes (" + (maxPasses - 1) + " at most) " + (relist
                        ? "in the same order again (someone else's list: Instagram ignores the order there)"
                        : "in the other orders (" + (own ? "your own list" : pageCacheFields == null ? "no page caches, no order listed twice"
                        : !refreshHooked() ? "no request hook with a refresh flag" : noRefresh ? "a page request without the refresh flag"
                        : "no page request of the first pass seen") + ")"));
                startPass(1);
                return;
            }
            int before = merged.size();
            int added = mergePass(ok ? "complete" : "failed: " + why);
            lastAdded = added;
            if (!ok) interrupted = true;
            boolean fellShort = false;
            if (ok && passRelist) {
                double predicted = FollowListPolicy.predictedNew(target(), before, counts.unique);
                if (FollowListPolicy.relistFellShort(predicted, added)) {
                    fellShort = true;
                    relist = false;
                    ModuleLog.detail(TAG + label() + ": the same order again added " + added + " of about " + Math.round(predicted)
                            + " predicted, so the next passes switch order");
                }
            }
            if (relist && noRefresh) {
                relist = false;
                ModuleLog.detail(TAG + label() + ": a page request went out without the refresh flag, so the next passes switch order");
            }
            if (ok && FollowListPolicy.anotherPass(pass + 1, maxPasses, merged.size(), target(), added, fellShort, budgetRows())) {
                startPass(pass + 1);
                return;
            }
            finish(true, why);
        }

        /** The count the passes aim for: Instagram's, or the most rows a pass received when more. */
        private int target() {
            return FollowListPolicy.target(expected, maxReceived);
        }

        /** The rows Instagram sends for this list in one pass, for FollowListPolicy.ROW_BUDGET. */
        private int budgetRows() {
            return Math.max(Math.max(maxReceived, expected), firstCounts != null ? firstCounts.rows : counts.rows);
        }

        /** One more pass listed these accounts (FollowListPolicy.passHits). */
        private void countHits(List<String> ids) {
            for (String id : ids) hits.merge(id, 1, Integer::sum);
        }

        /** Instagram's limit flag for this list can't be read: no page getter for it (and, on
         *  Followers lists, no response model getter either). */
        private boolean limitFlagMissing() {
            if (info != null && FollowListExportHook.FOLLOWING.equals(info.type)) return pageLimitedFollowing == null;
            if (pageLimited != null) return false;
            for (Object[] t : responseTypes) if (((Method[]) t[1])[R_LIMIT] != null) return false;
            return true;
        }

        /** Lists the list again from its first page for extra pass {@code n}, in the first pass's
         *  order (relist) or the cycle's next one (listFromStart: Instagram's sort handler clears the
         *  list and fetches that order's first page, from the server as the page caches were emptied). */
        private void startPass(int n) {
            pass = n;
            passPages = 0;
            passPageSeen = false;
            passMerged = false;
            fromStart = true;
            passRequests = 0;
            tally = new FollowListPolicy.PassTally();
            passStarted = lastProgressAt = SystemClock.uptimeMillis();
            cursor = null;
            cursors.clear();
            lastUnique = -1;
            retries = 0;
            retryAt = 0;
            lastFailure = null;
            passRelist = relist;
            passOrderName = relist ? firstOrderName : FollowListPolicy.passOrder(firstOrderName, ++cycled);
            Object order = relist ? firstOrder : order(orderField.getType(), passOrderName);
            if (order != null && order != firstOrder) orderChanged = true;
            passKey = order != null ? orderValue(order) : passOrderName;
            ModuleLog.detail(TAG + label() + ": extra pass " + n + " of up to " + (maxPasses - 1) + ", order " + keyName(passKey)
                    + (relist ? " again" : "") + " (listed " + merged.size() + " of " + target() + ")");
            if (order == null || !listFromStart(order)) {
                passMerged = true; // nothing listed in this order
                passLog.append(passLog.length() > 0 ? ", " : "").append(keyName(passKey)).append(" not set");
                finish(true, "sort failed");
                return;
            }
            poke();
        }

        /** Adds the accounts this extra pass listed that no pass listed before, logs how many and the
         *  pass's totals; returns how many it added. */
        private int mergePass(String how) {
            if (passMerged) return 0;
            passMerged = true;
            read(SystemClock.uptimeMillis(), true);
            List<String> fresh = FollowListPolicy.newIds(merged.keySet(), counts.ids);
            for (String id : fresh) merged.put(id, nameOf(id));
            countHits(counts.ids);
            passesDone++;
            maxReceived = Math.max(maxReceived, tally.received);
            ModuleLog.detail(TAG + label() + ": order " + keyName(passKey) + (passRelist ? " again" : "") + ": +" + fresh.size()
                    + " new (" + counts.unique + " listed in this pass, " + passPages + " pages, " + passRequests + " requests, "
                    + how + ") | pass " + (pass + 1) + ": " + tallyText());
            passLog.append(passLog.length() > 0 ? ", " : "").append(keyName(passKey)).append(passRelist ? " again" : "")
                    .append(" +").append(fresh.size()).append(how.equals("complete") ? "" : " (" + how + ")");
            return fresh.size();
        }

        /** This pass's pages as Instagram sent them (page hook). */
        private String tallyText() {
            FollowListPolicy.PassTally t = tally;
            return "received " + t.received + ", distinct " + t.distinct() + ", repeated " + t.repeated + " in " + t.pages
                    + " pages" + (t.withoutId > 0 ? ", " + t.withoutId + " without id" : "");
        }

        /** Sets the first pass's order back through Instagram's sort handler, which shows that
         *  order's pages again from its cache (or fetches them, when a later pass emptied it). */
        private void restoreOrder() {
            if (firstOrder == null || sortMethod == null) return;
            try {
                sortMethod.invoke(frag, firstOrder);
                ModuleLog.detail(TAG + label() + ": order set back to " + ((Enum<?>) firstOrder).name());
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "Could not set the list back to its first order: " + (t.getCause() != null ? t.getCause() : t));
            }
        }

        private Object currentOrder() {
            try {
                return orderField != null ? orderField.get(frag) : null;
            } catch (Throwable t) {
                return null;
            }
        }

        private String[] nameOf(String id) {
            String[] n = names.get(id);
            return n != null ? n : new String[2];
        }

        // ---- page diagnostics ----

        /** A page Instagram is about to apply (page hook): logged when it is this list's. */
        void page(Object step) throws Exception {
            if (finished || !ownsList(pageOwnerField.get(step))) { // another list's page (a neighbouring tab)
                rememberLimited(step);
                return;
            }
            Object page = pageField.get(step);
            String key = pageKeyField != null ? (String) pageKeyField.get(step) : null;
            Object limit = call(pageLimited, page);
            Object users = call(pageUsers, page);
            Object[] response = responseOf(page);
            Object model = response != null ? response[0] : null;
            Method[] g = response != null ? (Method[]) response[1] : new Method[RESPONSE_KEYS.length];
            if (limit == NOT_READ) limit = call(g[R_LIMIT], model);
            Object next = call(g[R_NEXT], model);
            // Following pages only: the Followers ones return false / 0
            boolean following = info != null && FollowListExportHook.FOLLOWING.equals(info.type);
            Object limitFollowing = following ? call(pageLimitedFollowing, page) : null;
            Object hidden = following ? call(pageHiddenFollowing, page) : null;
            Object token = call(pageToken, page);
            int n = users instanceof List<?> l ? l.size() : -1;
            pagesSeen++;
            if (n > 0) usersSeen += n;
            if (Boolean.TRUE.equals(limit) || Boolean.TRUE.equals(limitFollowing)) {
                pagesLimited++;
                limited = true;
            }
            if (hidden instanceof Integer h && h > hiddenFollowing) hiddenFollowing = h;
            // a page of this pass: of its order, and in a pass from the first page only once that pass
            // asked for a page (every pass in one order sends the same key, so a late page of the
            // previous pass would match it too; a first-pass page before that is a stale one)
            boolean keyed = key == null || passKey == null || passKey.equals(key);
            boolean ofPass = keyed && (!fromStart || passRequests > 0 || !hooked());
            if (fromStart && keyed && pass == 0 && !staleRetried && passRequests == 0 && hooked()) stalePage = true;
            if (fromStart && ofPass) passPageSeen = true;
            String tallied = "";
            if (users instanceof List<?> l && ofPass) {
                List<String> ids = new ArrayList<>(l.size());
                for (Object u : l) ids.add(u != null && u.getClass().getName().equals(USER) ? idOf(u) : null);
                FollowListPolicy.PassTally t = tally;
                int[] c = t.page(ids);
                tallied = " | pass " + (pass + 1) + ": " + c[FollowListPolicy.P_NEW] + " new, " + c[FollowListPolicy.P_REPEATED]
                        + " repeated" + (c[FollowListPolicy.P_REPEATED] > 0 ? " (" + repeatsBack(c) + ")" : "")
                        + " (received " + t.received + ", distinct " + t.distinct() + ")";
            } else if (users instanceof List<?>) {
                tallied = " | not counted (" + (keyed ? "before this pass's first request" : "another order's page") + ")";
            }
            String tokenText;
            if (token instanceof String s) {
                tokenText = Integer.toHexString(s.hashCode()) + (s.equals(lastToken) ? " (same)" : lastToken != null ? " (changed)" : "");
                lastToken = s;
            } else tokenText = token == null ? "none" : "?";
            StringBuilder unfetched = new StringBuilder();
            for (int k : new int[]{R_HAS_MORE, R_BIG_LIST, R_PAGE_SIZE, R_TRUNCATE}) {
                Object v = call(g[k], model);
                if (v != null && v != NOT_READ) unfetched.append(", ").append(RESPONSE_KEYS[k]).append('=').append(v);
            }
            ModuleLog.probe(TAG + "page " + pagesSeen + " " + label() + " (order " + keyName(key)
                    + "): " + (n >= 0 ? String.valueOf(n) : "?") + " users, should_limit=" + limit
                    + (following ? ", should_limit_followings=" + limitFollowing + ", hidden_following=" + hidden : "")
                    + ", " + source(page) + ", next_max_id=" + cursorShape(next) + ", ranking token " + tokenText
                    + unfetched + (response == null ? ", no response model" : "") + tallied);
        }

        /** A page's repeats by how many pages back the last page that sent them was. */
        private String repeatsBack(int[] c) {
            return "1 back " + c[FollowListPolicy.P_BACK1] + ", 2 back " + c[FollowListPolicy.P_BACK2] + ", 3-5 back "
                    + c[FollowListPolicy.P_BACK3_5] + ", older " + c[FollowListPolicy.P_OLDER]
                    + (c[FollowListPolicy.P_SAME] > 0 ? ", same page " + c[FollowListPolicy.P_SAME] : "");
        }

        /** The page handler's owner (the list's response handler) belongs to this list's fragment. */
        private boolean ownsList(Object owner) {
            if (owner == null) return false;
            if (owner == frag) return true;
            try {
                Field f = ownerFragField;
                if (f != null && f.getDeclaringClass().isInstance(owner)) return f.get(owner) == frag;
                for (Field c : SafeReflect.fields(owner.getClass())) {
                    if (Modifier.isStatic(c.getModifiers()) || c.getType().isPrimitive()) continue;
                    c.setAccessible(true);
                    if (c.get(owner) == frag) {
                        ownerFragField = c;
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
            return false;
        }

        /** Instagram's failed flag: its pager says "not loading" although a next page exists. */
        private boolean failed() {
            try {
                return pagerIsLoading != null && !(boolean) pagerIsLoading.invoke(pager);
            } catch (Throwable t) {
                return false;
            }
        }

        private void ask(Method m, long now) {
            lastAskAt = now;
            try {
                m.invoke(pager);
            } catch (Throwable t) {
                ModuleLog.detail(TAG + m.getName() + ": " + (t.getCause() != null ? t.getCause() : t));
            }
        }

        /** Reads the adapter's account list. Only an adapter without any List field (not 451's) is
         *  walked instead, at most every 2 s: a walk also finds suggested accounts, so it must never
         *  stand in for an account list that is merely still empty. */
        private void read(long now, boolean last) {
            if (lists != null && lists.length > 0) {
                List<?> main = mainList();
                List<String> ids = new ArrayList<>(main.size());
                for (int i = 0; i < main.size(); i++) {
                    Object u = main.get(i);
                    String id = u != null && u.getClass().getName().equals(USER) ? idOf(u) : null;
                    ids.add(id);
                    if (id != null && !names.containsKey(id)) names.put(id, new String[]{username(u), fullName(u)});
                }
                counts = FollowListPolicy.count(ids);
                walked = false;
                return;
            }
            if (adapter == null || (!last && now - lastWalkAt < 2000)) return;
            lastWalkAt = now;
            boolean[] cut = new boolean[1];
            Map<String, String[]> found = walk(adapter, cut);
            names.putAll(found);
            counts = FollowListPolicy.count(new ArrayList<>(found.keySet()));
            walked = true;
            walkCut = cut[0];
        }

        /** The adapter's account list: its List field holding Users that grew while pages came in,
         *  else the largest (FollowListPolicy.mainList); empty while no list holds a User yet. */
        private List<?> mainList() {
            List<List<?>> users = new ArrayList<>();
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < lists.length; i++) {
                Object v;
                try { v = lists[i].get(adapter); } catch (Throwable t) { continue; }
                int n = v instanceof List<?> l ? l.size() : 0;
                if (n > sizes[i]) growth[i]++;
                sizes[i] = n;
                if (v instanceof List<?> l && holdsUsers(l)) {
                    users.add(l);
                    idx.add(i);
                }
            }
            if (users.isEmpty()) return Collections.emptyList();
            int[] s = new int[users.size()], g = new int[users.size()];
            for (int i = 0; i < s.length; i++) {
                s[i] = users.get(i).size();
                g[i] = growth[idx.get(i)];
            }
            return users.get(FollowListPolicy.mainList(s, g));
        }

        private void progress(long now) {
            if (dlg == null) return;
            try {
                Context c = dlg.ctx;
                // in an extra pass: the accounts of the passes so far plus this pass's new ones
                int fresh = pass > 0 ? FollowListPolicy.newIds(merged.keySet(), counts.ids).size() : 0;
                int n = pass > 0 ? merged.size() + fresh : counts.unique;
                String msg = expected > 0 ? I18n.t(c, R.string.ig_follow_export_loading_of, n, expected)
                        : I18n.t(c, R.string.ig_follow_export_loading, n);
                if (pass > 0) msg += "\n\n" + I18n.t(c, R.string.ig_follow_export_pass, pass, maxPasses - 1,
                        I18n.t(c, FollowListPolicy.ORDER_EARLIEST.equals(passOrderName) ? R.string.ig_follow_export_order_earliest
                                : FollowListPolicy.ORDER_LATEST.equals(passOrderName) ? R.string.ig_follow_export_order_latest
                                : R.string.ig_follow_export_order_default), fresh);
                if (retryAt != 0) msg += "\n\n" + I18n.t(c, R.string.ig_follow_export_retry,
                        (int) Math.max(1, (retryAt - now + 999) / 1000));
                dlg.message(msg);
            } catch (Throwable ignored) {}
        }

        /**
         * Ends the load and hands over its result. During an extra pass (stopped, left, failed or
         * done) the result is every pass's accounts, complete as the first pass was unless Instagram
         * blocked the load (an extra pass that ended early marks it interrupted), and the first
         * pass's order is set back when a pass ran in another one. A complete, unblocked load ends a
         * pause after an earlier block for this account.
         */
        void finish(boolean ok, String why) {
            if (finished) return;
            finished = true;
            if (current == this) current = null;
            H.removeCallbacks(this);
            try {
                if (observer != null && list != null && list.getAdapter() != null) list.getAdapter().unregisterDataSetObserver(observer);
            } catch (Throwable ignored) {}
            try { if (dlg != null) dlg.dismiss(); } catch (Throwable ignored) {}
            if (adapter == null) {
                list = FollowListExportHook.findList(root);
                adapter = adapterOf(list);
                if (adapter != null) lists = listFields(adapter.getClass()).toArray(new Field[0]);
                if (lists != null) { sizes = new int[lists.length]; growth = new int[lists.length]; }
            }
            Map<String, String[]> users = new LinkedHashMap<>();
            FollowListPolicy.Counts c;
            boolean complete;
            if (pass > 0) {
                if (!passMerged) {
                    interrupted = true;
                    try { lastAdded = mergePass(stopped ? "stopped" : why); } catch (Throwable t) { ModuleLog.detail(TAG + "read: " + t); }
                }
                users.putAll(merged);
                c = firstCounts;
                complete = blocked == null;
                if (orderChanged) restoreOrder();
            } else {
                try { read(SystemClock.uptimeMillis(), true); } catch (Throwable t) { ModuleLog.detail(TAG + "read: " + t); }
                for (String id : counts.ids) users.put(id, nameOf(id));
                c = counts;
                complete = ok && !stopped && !walkCut && blocked == null;
                if (passesDone == 0) {
                    countHits(counts.ids);
                    passesDone = 1;
                }
                if (firstReceived < 0 && tally.pages > 0) {
                    firstReceived = tally.received;
                    firstRepeated = tally.repeated;
                }
            }
            int[] f = FollowListPolicy.passHits(hits.values(), passesDone);
            int f1 = f.length > 1 ? f[1] : 0, f2 = f.length > 2 ? f[2] : 0;
            double estMissing = FollowListPolicy.missingEstimate(f1, f2, passesDone);
            if (passesDone > 1) {
                StringBuilder fs = new StringBuilder();
                for (int k = 1; k < f.length; k++) fs.append(k > 1 ? ", " : "").append('f').append(k).append('=').append(f[k]);
                ModuleLog.detail(TAG + label() + ": accounts by how many of the " + passesDone + " passes listed them: " + fs
                        + "; Chao1 estimate of accounts no pass listed: " + Math.round(estMissing * 10) / 10.0);
            }
            ModuleLog.detail(TAG + "loaded " + label() + ": " + users.size() + " accounts (" + c.rows + " rows, "
                    + c.duplicates + " repeated, " + c.withoutId + " without id"
                    + (walked ? ", adapter walk" + (walkCut ? " cut at its cap" : "") : "") + ")"
                    + (expected >= 0 ? ", Instagram counts " + expected : "") + ", " + pages + " pages, "
                    + retriesTotal + " retries, " + (SystemClock.uptimeMillis() - started) / 100 / 10.0 + " s, "
                    + (complete ? "complete" + (interrupted ? " (an extra pass ended early)" : "") : "unfinished: " + (walkCut ? "walk cut" : why))
                    + " | pages seen " + pagesSeen + ", limited " + pagesLimited + (limited ? " (limited)" : "")
                    + (hiddenFollowing > 0 ? ", hidden following " + hiddenFollowing : "") + ", users " + usersSeen
                    + (pass == 0 && firstCounts == null ? " | pass 1 so far: " + tallyText() : "")
                    + (passLog.length() > 0 ? " | extra passes: " + passLog : "")
                    + (blocked != null ? " | blocked: " + blocked : ""));
            if (complete) clearPause(root.getContext(), info != null ? info.self : null);
            try {
                done.accept(new Result(users, complete, blocked, interrupted, c.rows, c.duplicates, c.withoutId, expected, limited,
                        hiddenFollowing, firstReceived, firstRepeated, Math.max(1, passesDone), passesDone > 1 ? lastAdded : -1,
                        f1, f2, estMissing, own));
            } catch (Throwable t) {
                ModuleLog.error(TAG + "Could not finish after loading the list", t);
            }
        }

        private String label() {
            return (info != null ? info.type + " of " + info.owner : "list");
        }
    }

    // ---- reading Users ----

    private static Object adapterOf(AbsListView lv) {
        Adapter ad = lv != null ? lv.getAdapter() : null;
        if (ad instanceof HeaderViewListAdapter h) ad = h.getWrappedAdapter();
        return ad;
    }

    /** The adapter class's own List fields (not its Instagram base adapter's row bookkeeping). */
    private static List<Field> listFields(Class<?> adapterClass) {
        List<Field> out = new ArrayList<>();
        for (Field f : SafeReflect.fields(adapterClass)) {
            if (Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) continue;
            try { f.setAccessible(true); out.add(f); } catch (Throwable ignored) {}
        }
        return out;
    }

    private static int sizeOf(Field f, Object adapter) {
        try {
            return f.get(adapter) instanceof List<?> l ? l.size() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean holdsUsers(List<?> l) {
        for (int i = 0, n = Math.min(l.size(), 3); i < n; i++) {
            Object o = l.get(i);
            if (o != null) return o.getClass().getName().equals(USER);
        }
        return false;
    }

    /** The User's id: its final numeric String field (451: A09, the pk). */
    static String idOf(Object user) {
        Field f = userIdField;
        try {
            if (f == null) {
                for (Field c : SafeReflect.fields(user.getClass())) {
                    if (c.getType() != String.class || Modifier.isStatic(c.getModifiers()) || !Modifier.isFinal(c.getModifiers())) continue;
                    c.setAccessible(true);
                    if (c.get(user) instanceof String s && s.matches("\\d+")) { userIdField = f = c; break; }
                }
                if (f == null) return null;
            }
            return f.get(user) instanceof String s && !s.isEmpty() ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The username, from the getter reading "username" only (UserUtils' fallback guesses getters). */
    static String username(Object user) {
        Method m = usernameGetter != null ? usernameGetter : UserUtils.userUsernameGetter;
        if (m == null) return null;
        try {
            return m.invoke(user) instanceof String s && !s.isEmpty() ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static String fullName(Object user) {
        try {
            return fullNameGetter != null && fullNameGetter.invoke(user) instanceof String s && !s.isEmpty() ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Instagram's count for the list on screen: follower_count / following_count of the profile's
     * User (the fragment's User field holding the list owner), -1 when unknown. Not on your own
     * lists while the profile spoofer shows other counts.
     */
    static int expectedCount(Object frag, FollowListExportHook.ListInfo info) {
        if (info == null || info.type == null) return -1;
        Method getter = FollowListExportHook.FOLLOWERS.equals(info.type) ? followerCountGetter : followingCountGetter;
        if (getter == null) return -1;
        if (FeatureFlags.profileSpoofer && info.owner.equals(info.self)
                && (FollowListExportHook.FOLLOWERS.equals(info.type) ? FeatureFlags.spoofFollowers : FeatureFlags.spoofFollowing) >= 0)
            return -1;
        try {
            for (Class<?> c = frag.getClass(); c != null && c != Object.class; c = c.getSuperclass())
                for (Field f : SafeReflect.fields(c)) {
                    if (Modifier.isStatic(f.getModifiers()) || !f.getType().getName().equals(USER)) continue;
                    f.setAccessible(true);
                    Object u = f.get(frag);
                    if (u == null || !info.owner.equals(idOf(u))) continue;
                    Object n = getter.invoke(u);
                    return n instanceof Integer i && i >= 0 ? i : -1;
                }
        } catch (Throwable ignored) {}
        return -1;
    }

    /** Fallback when the adapter has no account list: id → {username, full name} for every User
     *  reachable from the adapter through Lists / Maps; {@code cut[0]} when it stopped at its cap. */
    private static Map<String, String[]> walk(Object adapter, boolean[] cut) {
        Map<String, String[]> out = new LinkedHashMap<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[]{adapter, 0, false}); // {object, depth, reached through a List/Map (= a row)}
        while (!q.isEmpty()) {
            if (seen.size() >= 20000) { cut[0] = true; break; }
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            boolean row = (boolean) e[2];
            if (o == null || !seen.add(o)) continue;
            if (o instanceof List<?> l) { for (Object it : l) q.add(new Object[]{it, d + 1, true}); continue; }
            if (o instanceof Map<?, ?> m) { for (Object it : m.values()) q.add(new Object[]{it, d + 1, true}); continue; }
            if (o.getClass().getName().equals(USER)) {
                if (!row) continue; // the profile owner / logged-in user held in plain fields, not a list row
                String id = idOf(o);
                if (id != null) out.putIfAbsent(id, new String[]{username(o), fullName(o)});
                continue;
            }
            if (d >= 4) continue;
            String n = o.getClass().getName();
            if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : SafeReflect.fields(k)) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class
                            || Context.class.isAssignableFrom(f.getType()) || View.class.isAssignableFrom(f.getType())) continue;
                    try { f.setAccessible(true); q.add(new Object[]{f.get(o), d + 1, row}); } catch (Throwable ignored) {}
                }
        }
        return out;
    }

    private static IeDialog progressDialog(Context c, CharSequence title) {
        try {
            // the Activity that hosts the list (UIHookManager's current one can be a different, hidden one)
            Context ctx = c;
            while (!(ctx instanceof Activity) && ctx instanceof android.content.ContextWrapper w && w.getBaseContext() != null) ctx = w.getBaseContext();
            if (!(ctx instanceof Activity)) {
                Activity act = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
                ctx = act != null ? act : c;
            }
            IeDialog d = new IeDialog(ctx)
                    .title(title)
                    .message(I18n.t(ctx, R.string.ig_follow_export_loading, 0))
                    .cancelable(false); // leaving the list would stop IG's pager
            android.widget.ProgressBar bar = new android.widget.ProgressBar(d.ctx, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true);
            bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(d.accent));
            d.view(bar);
            d.primary(I18n.t(ctx, R.string.ig_follow_export_stop), FollowListLoader::stop);
            d.onClose(FollowListLoader::stop);
            d.show();
            return d;
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not show the loading progress; the list still loads", t);
            return null;
        }
    }
}
