<div align="center">
  <img src="assets/logo.png" alt="InstaEclipse" width="112" />
  <h1>InstaEclipse</h1>
  <p><b>Instagram, the way you want it.</b></p>
  <p>A module that adds a menu inside Instagram for privacy, downloads, a calmer feed and your own look.<br/>
  This is <b>LumixCode's fork</b> of <a href="https://github.com/ReSo7200/InstaEclipse">ReSo7200/InstaEclipse</a> 0.8.2, with its own features and fixes.</p>

  <p>
    <a href="https://github.com/LumixCode/InstaEclipse/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/LumixCode/InstaEclipse?style=for-the-badge&logo=github&label=release&color=1a1a2e&labelColor=0d0d0d"/></a>
    <a href="https://github.com/ReSo7200/InstaEclipse/releases/tag/v0.8.2"><img alt="Based on InstaEclipse 0.8.2" src="https://img.shields.io/badge/based%20on-InstaEclipse%200.8.2-1a1a2e?style=for-the-badge&labelColor=0d0d0d"/></a>
    <a href="#compatibility"><img alt="Instagram 451.0.0.20.80" src="https://img.shields.io/badge/Instagram-451.0.0.20.80-E4405F?style=for-the-badge&logo=instagram&logoColor=white&labelColor=0d0d0d"/></a>
    <a href="#compatibility"><img alt="Android 9 and newer" src="https://img.shields.io/badge/Android-9%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white&labelColor=0d0d0d"/></a>
    <a href="LICENSE"><img alt="License GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-1a1a2e?style=for-the-badge&labelColor=0d0d0d"/></a>
  </p>

  <h3>
    <a href="https://github.com/LumixCode/InstaEclipse/releases/latest">Download</a> ·
    <a href="#get-started">Get started</a> ·
    <a href="#whats-new-in-this-fork">What's new</a> ·
    <a href="#features">Features</a> ·
    <a href="#faq">FAQ</a>
  </h3>
</div>

---

## At a glance

- 👻 **Ghost mode.** Read chats, watch stories and lives, and screenshot disappearing media without anyone being told.
- 📥 **Downloads.** Save posts, reels, stories, highlights, profile pictures, voice messages, stickers and GIFs.
- 💬 **Nothing disappears.** Disappearing messages, unsent messages and earlier versions of edited messages stay readable.
- 🔒 **Locks.** Put a passcode on Instagram, your inbox, single chats or Saved collections, and hide chats from your inbox.
- 🧘 **Focus mode.** Turn off the feed, stories, Reels, Explore or comments, or give yourself a daily reel limit and a night block.
- 🎨 **Your look.** Custom themes for light and dark mode, your own font and emoji, and your own tab order.

Everything is an option in the InstaEclipse menu: **hold the Search tab** in Instagram and it slides up. Most changes apply right away. A companion app shows whether the module is running, plus logs, backups and help.

> [!IMPORTANT]
> This is an unofficial fork. Please don't report problems with it to the upstream project. InstaEclipse is a personal, educational project and is not affiliated with Meta or Instagram. See the [Disclaimer](DISCLAIMER.md).

---

## Get started

**You need:** an Android 9+ phone, and [Instagram **451.0.0.20.80** from APKMirror](https://www.apkmirror.com/apk/instagram/instagram-instagram/instagram-451-0-0-20-80-release/), the only Instagram version this fork supports. The Play Store build is not always fully supported.

Download `InstaEclipse-<version>.apk` from [**Releases**](https://github.com/LumixCode/InstaEclipse/releases/latest), then pick your path.

<table>
<tr>
<th width="50%">📱 No root: LSPatch</th>
<th width="50%">🔓 Rooted: Vector</th>
</tr>
<tr>
<td valign="top">

1. Install the InstaEclipse APK.
2. Install [JingMatrix's LSPatch](https://github.com/JingMatrix/LSPatch/releases/latest) (1.0 or newer).
3. In LSPatch, tap **+**, pick the Instagram APK, choose **Local Patch Mode**, turn on **Inject loader dex** and tap **Start Patch**.
4. Install the patched Instagram and log in.
5. In LSPatch, open **Manage › Instagram › Modules** and enable InstaEclipse.

</td>
<td valign="top">

1. Install the InstaEclipse APK.
2. In the [Vector](https://github.com/JingMatrix/Vector/releases/latest) manager (2.2 or newer), open **Modules**, enable InstaEclipse and tick Instagram.
3. Force stop Instagram and open it again.

</td>
</tr>
</table>

Then **hold the Search tab** in Instagram to open the menu.

> [!WARNING]
> **Coming from the official InstaEclipse?** This fork has the same package name but a different signing key, so Android won't install it over the official app. Back up your settings in the companion app, uninstall it, install this one and restore the backup. Releases of this fork install over each other.

> [!CAUTION]
> **Using Hide My Applist?** Don't hide InstaEclipse from Instagram. Instagram needs to see it, or the menu text goes missing and features stop working.

### Shortcuts inside Instagram

| Gesture | What it does |
|---|---|
| Hold the **Search** tab | Opens the InstaEclipse menu. The search box at the top finds any option, also by its old name |
| Hold the **Messages** icon | Turns the Ghost mode options you chose on or off together |
| Tap the **InstaEclipse button** in a chat | Opens the chat menu: unsent messages, export, jump to first message, read receipts, hide and lock. If you hid the button, triple-tap between the chat name and the call buttons |
| Triple-tap **your name** in Messages | Shows your hidden chats after you unlock |
| Hold the **left side** of a reel | Plays it at 0.5×; the right side still plays at 2× |
| Hold a **caption** or a **comment** | Copies it; a comment's GIF or image can be saved too |
| Open a story's **⋮** menu | Story details, mentions, links, playback speed and downloads |

You can find these again in the menu under **About › Gestures**. The chip at the top of the menu shows how many of your features are active (like *62/62 active*). Tap it to see which ones couldn't attach or wait for a restart.

---

## What's new in this fork

Compared with [upstream InstaEclipse 0.8.2](https://github.com/ReSo7200/InstaEclipse/releases/tag/v0.8.2). Each change is its own small commit; see the [commit history](https://github.com/LumixCode/InstaEclipse/commits/main) or the [full comparison](https://github.com/ReSo7200/InstaEclipse/compare/main...LumixCode:InstaEclipse:main).

### 🆕 New

| Feature | What you get |
|---|---|
| **Unsent messages in red** | Kept unsent messages are marked: a red bubble for text, a red fill for voice messages and a red outline for other media |
| **Unsent media details** | Unsent photos, reels, voice messages and links are listed by what they were, like *Reel by @user* or *Voice message · 0:29* |
| **Lock chosen chats** | A passcode on just the chats you pick, from the menu or with *Lock this chat* in a chat's menu |
| **Forgot passcode?** | Confirm with your phone's screen lock (Android 11+) to remove the passcode and turn the locks off |
| **Daily reel limit** | 1 to 10000 reels a day (100 by default), with today's count in the menu and warnings when 10 and 5 are left |
| **Night reel block** | No reels during quiet hours (22:00 to 07:00 by default). Both reel limits can still allow reels sent in chats |
| **Who follows you back** | *Follows you* or *Doesn't follow you* under each account in your own Following list |
| **Follower changes on profiles** | With the Profile analyzer on, a profile shows +N / −N next to its followers and following since your last check |
| **Unlock Plus features** | Turns on the Instagram Plus features that Instagram checks on your phone. Only on this phone; your account doesn't get Plus |
| **Search inside Instagram** | The menu inside Instagram has a search box for every option |
| **Crash reports** | The companion's Logs tab shows how Instagram's earlier sessions ended (Android 11+) and keeps recent crash reports you can share or delete |
| **Video covers** | The download sheet can also save a video's cover image |

### ✨ Works differently

| Feature | Upstream 0.8.2 | This fork |
|---|---|---|
| Keep unsent messages | Logs unsent text. Keeping it in the chat is opt-in, for received messages, until Instagram restarts | Messages you or others unsend stay in the chat, also after Instagram restarts, and are logged per account and chat |
| Earlier versions of edits | Shown through Instagram's own view, until Instagram restarts | Tap *Edited* on a message to see every earlier version with its time, kept after restarts |
| Hide chosen chats | Filtered out of the inbox, managed in a list of hidden chats | Pick them from your chats or the chat menu; triple-tap your name in Messages and unlock to see only the hidden ones |
| Send Seen in chosen chats | Set per chat from the chat menu | Also one list to see and edit every chat that still gets Seen |
| Chat menu button | Always in the chat header | Can be hidden: a triple tap on the empty header space opens the same menu |
| Jump to first message | Tap its entry again to stop | Also stops when you scroll yourself, or with its Stop button |
| Mark a story as seen | Replays a blocked view request | Its own switch: sends the view for that one story, once, while other views stay hidden |
| Story cache | Fixed 24 hours | 1 to 720 hours (24 by default); turning it off offers to delete the cached stories |
| Exact dates | Rewrites every "2h ago", inbox included | Posts, comments and stories in your format, chat times with seconds, and the inbox keeps Instagram's short times |
| Copy captions | *Copy caption* row in the post ⋮ menu | Hold a caption in the feed, in Reels or at the top of the comment sheet |
| Comment long press | InstaEclipse's own dialog | *Copy comment*, *Download GIF* and *Download image* right in Instagram's menu, saving the comment's own media |
| Comment search | A grey "Search comments" pill | Instagram's own search icon in the sort row |
| Follow status on profiles | Coloured "Follows you" or "Doesn't follow you" after the name | A grey line that also shows when you follow each other |
| Copy username and bio | Rows in the profile ⋮ menu | Hold the username or the bio |
| Hide the Repost button | Blocks reposting; the buttons stay | Also removes the repost buttons from posts and reels |
| Wait before sending likes | An UNDO bar; after Undo the heart stays filled until the post is refreshed | No bar: the heart is blue for 4 seconds, then red once the like is sent. Tap it again to take the like back. Works on story likes too |
| Profile analyzer | Followers history is a separate feature | *Check my followers* is part of the analyzer, one tools icon sits on follower lists, and lists reload until complete so missing accounts aren't reported as unfollows |
| Skip contact and location prompts | Skips the two setup pages through one of the routes Instagram uses | Blocks every route to them, and declines Instagram's location requests without a popup while it has no location access |
| Restart Instagram | Always clears Instagram's cache | *Restart now* keeps the cache; clearing it is a separate button |

<details>
<summary><b>🔧 Fixes to upstream features</b></summary>

<br/>

- **Downloads:** each carousel slide saves its own video, never the post's music; audio and GIFs keep their real format; Giphy GIFs save to your folder; a story opened from a post saves the story, not the post; Reel playback quality no longer limits which qualities downloads can pick from.
- **Chats:** downloading a voice message, sticker or GIF saves only the one you pressed, and Instagram's own Copy keeps working; long chat and comment exports are written inside Instagram instead of failing.
- **Ghost mode:** Open view-once secretly and Keep disappearing messages no longer leave later messages stuck on *Sending*; the mark-as-read button shows under embedded LSPatch.
- **Locks:** an empty passcode counts as a wrong try instead of crashing; five wrong tries mean a 30 second wait, also after a restart; turning a lock off needs the passcode; while a passcode is set, the companion can turn locks on but not off; Lock Instagram doesn't ask again after a quick trip (under a minute) to a photo picker, the camera or a link.
- **Stories:** exact dates in the story header wrap instead of being cut off; the music service logos in Story details show under LSPatch.
- **Reels:** hold for slow motion slows only the reel under your finger.
- **Instants:** a gallery photo picked for an Instant is dropped when you leave the camera.
- **Focus mode:** turning a switch off brings the hidden tab, tray or button back right away; Turn off Explore also hides search's top results.
- **Feed:** Hide suggested posts keeps *You're all caught up*, follow requests and Take a break.
- **Ads and analytics:** Block ads also removes the ads between reels and in the comment sheet; Block analytics only stops usage reports, so payments, maps and Meta Verified keep working.
- **Links:** tracking codes are removed even under renamed parameters, while login and app links keep what they need; links in Instagram's browser sheets open in your browser too; Threads links stay in Instagram; a custom share domain can be an embed mirror such as ddinstagram.com.
- **Remove Meta AI:** no longer hides unrelated parts of Reels and search, and also removes the Meta AI rows Instagram adds one at a time to post and reel ⋮ menus.
- **Share sheet:** Hide "Create group" never lets it flash on screen, and the app row is evenly spaced.
- **Location:** nearby Wi-Fi, cell towers and Bluetooth are hidden from Instagram, so they can't give your real location away.
- **Themes and fonts:** theme changes apply live with a single refresh; Instagram's greys map to the right theme colours; a custom font keeps bold, italic and Instagram's icon fonts.
- **Stability and speed:** Ask before liking and Change counts on your posts no longer risk crashing Instagram while they are off; many hooks are installed only while their feature is on; fewer leaks; for Instagram 451.0.0.20.80 the module ships its code lookups, so the first start barely has to search.
- **Companion app and backups:** settings changed while Instagram was closed apply on its next start, also under embedded LSPatch; settings backups include number and text settings; a settings restore never turns a passcode lock off; a data restore never brings back a cleared unsent message and never unhides or renames a chat.
- **Security:** Instagram only takes settings and restores from the real companion app, also under embedded LSPatch, and the companion can't turn Extreme mode or a passcode lock off behind Instagram's back.
- **Updates:** the update check follows this fork's releases and stays quiet when you're offline.

</details>

### ➖ Not in this fork

- The **Legacy build** (`-L`) for the classic Xposed API. This fork builds only for the new API (102).
- **Spoof last seen.** It did nothing on Instagram 451. To hide when you're active, turn off Activity status in Instagram's settings.
- **Translate captions and comments.** Hold-to-copy replaced their menus. Chat messages can still be sent to your translator app.
- Upstream's optional **eye and trash icons** in the chat header and the long press on the chat name. Their actions are in the chat menu.

---

## Features

The whole menu, grouped the way it is inside Instagram. Option names match the menu, so you can search for them there. 🆕 marks what's new in this fork, ✨ what works differently than upstream.

<details>
<summary><b>🛡️ Privacy</b>: Ghost mode, locks, ads and location</summary>

#### 👻 Ghost mode

| Option | What it does |
|---|---|
| Hide Seen in chats | People don't see that you read their messages |
| Hide typing indicator | Type without the other person seeing the dots |
| View stories anonymously | You don't appear in the viewer list |
| Watch lives anonymously | Join lives without being counted |
| Don't report screenshots | The sender isn't told when you screenshot disappearing media |
| Open view-once secretly | The sender isn't told that you opened it |
| Allow screenshots and screen recording | Also where Instagram blocks them, such as disappearing media |
| Replay view-once media | View-once and view-twice photos and videos open like normal ones |
| ✨ Mark stories seen by hand | With View stories anonymously on, *Mark this story as seen* in the story ⋮ menu sends the view for that one story |
| ✨ Send Seen in chosen chats | With Hide Seen on, the chats you choose still get Seen. Manage them in one list |
| Appear offline | Hides *Active now*; messages and calls stop arriving live |
| Quick toggle | Pick the options that holding the Messages icon turns on or off |

#### 🔒 Lock & hidden chats

| Option | What it does |
|---|---|
| Lock Instagram | Passcode or fingerprint when Instagram opens |
| Lock Messages | Asks for the passcode before your inbox opens |
| 🆕 Lock chosen chats | Lock only the chats you pick |
| Lock again when you leave | Asks again after you leave Messages or a locked chat |
| Lock Saved collections | Put a passcode on the collections you pick |
| ✨ Hide chosen chats | They leave your inbox but still notify and show in search |
| 🆕 Forgot passcode? | Confirm with your phone's screen lock (Android 11+) to start over |

#### 🚫 Ads & tracking

| Option | What it does |
|---|---|
| Block ads | Sponsored posts, stories and reels, including the ads between reels |
| Block analytics | Stops Instagram's usage reports to Meta |
| ✨ Remove tracking from links | Strips tracking codes from links you copy or share |
| ✨ Open links in your browser | Instead of Instagram's built-in browser. Instagram, Facebook and Threads links stay in the app |
| Share domain | Replaces instagram.com in links you copy or share |
| Don't save searches | What you open from Search stays out of Recent, on your phone and on Instagram's servers |

#### 📍 Location

| Option | What it does |
|---|---|
| ✨ Use a fake location | Instagram sees the place you pick on a map; nearby Wi-Fi, cell towers and Bluetooth are hidden too. Location saved inside your photos is left as it is |

</details>

<details>
<summary><b>📥 Media</b>: downloads and quality</summary>

#### ⬇️ Downloads

| Option | What it does |
|---|---|
| Download posts | Photos, videos and carousels: the slide on screen, all slides, or the ones you pick |
| Download stories | Stories, and a whole highlight from its long-press menu |
| Download Reels | Reels, from their ⋮ menu |
| Download profile pictures | Hold an enlarged profile picture to see the full-size original and save it |
| Download photos and videos | Instagram's own Save in a chat goes through InstaEclipse, named after the sender |
| ✨ Download voice messages | Long-press a voice message; named after the sender |
| ✨ Download stickers and GIFs | Long-press a sticker or GIF in a chat |
| Save Instants | Long-press a received Instant |
| Copy media link | The direct link to the photo or video file, also for a single carousel slide |
| Copy image | Copies the photo to the clipboard |
| Show media info | Size, length, codec, available qualities and the song, with *Open with* |
| Quality | *Standard* saves what Instagram shows you; *Best* saves the sharpest version, HDR included, or the sharpest up to a resolution you pick |
| Prefer videos that play everywhere | Picks the most compatible video when there's a choice |
| Use lower quality if Best fails | Offers to save a lower quality instead |
| Offer audio-only downloads | Asks *Video* or *Audio* when you download a video; audio is saved as .m4a |
| Download folder | Chosen in the companion app, with a folder per account and the date in file names if you like |
| ✨ Cache viewed stories | Watch stories again after they expire, for 1 to 720 hours |

Without a switch:

- **Download all posts** of a profile from its ⋮ menu: the newest 12, 30, 50 or 100, all of them, or a number you choose.
- **Save a frame** of a video as a full-resolution PNG, stepping through it frame by frame (Android 11+).
- **Share to InstaEclipse:** share a post or reel link from any app to download it.

#### 🎞️ Quality

| Option | What it does |
|---|---|
| Reel playback quality | Auto, 360p to 1080p, or the highest available |
| Load the sharpest photos | Loads sharper versions of photos than Instagram picks |
| Always upload at highest quality | Turns on Instagram's own high-quality upload |
| Device Instagram sees | Instagram thinks it runs on a Pixel 11 Pro XL or Galaxy S26 Ultra, for features it keeps for those phones. Adds the matching developer options |

</details>

<details>
<summary><b>🧭 Browse</b>: feed, stories, Reels and Focus mode</summary>

#### 🏠 Feed

| Option | What it does |
|---|---|
| ✨ Hide suggested posts | Removes the suggestion blocks Instagram mixes into your feed, such as suggested reels and accounts |
| ✨ Hide Threads posts | Removes Threads posts, and the Threads button on your own profile |
| Hide posts you already liked | Drops them from the feed |
| Hide community notes | Removes notes added under posts and reels |
| Show only accounts you follow | A feed of the people you follow |
| ✨ Hide people suggestions | *Discover people* in the feed, on profiles and in Messages |
| Show full captions | No more "… more" |
| ✨ Show exact dates and times | Instead of "2h ago", in the format you pick |
| ✨ Hold a photo to zoom | Opens the sharp photo; swipe down to close |
| Don't autoplay videos | Videos start when you tap play |
| Ask before refreshing | So a pull doesn't lose your place in the feed |
| Rounded post corners | 8 to 24 dp |

#### 📖 Stories

| Option | What it does |
|---|---|
| Don't auto-advance stories | A story stays on screen when it ends |
| Show story mentions | Every @mention at once: tap to open the profile, hold to copy |
| Copy story links | Copies a link sticker's link, or lets you pick when there are several |
| Show story details and answers | Exact posted, expiry and edit times, music, location, hashtags, alt text, collaborators and more (tap a line to copy it), poll and quiz results as bars, and a search for the song on Spotify, YouTube Music or Apple Music |
| Search your story viewers | A search box over your viewer list |
| Hide the story tray | Stories still open from profiles |
| Story tray size | Fit more stories in a row |
| Unseen story ring color | Instagram's colours, one colour, or a gradient of 2 to 4, with presets, sliders and hex |

#### 🎬 Reels

| Option | What it does |
|---|---|
| Unlock Reels auto-scroll | Adds Instagram's own Auto scroll option to Reels |
| Lock swiping between reels | No accidental swipes |
| Always show the seek bar | Scrub through any reel |
| ✨ Hold left side for slow motion | 0.5× on the left half; the right half stays 2× |
| Change playback speed | 0.5× to 3× from the ⋮ menu of videos, reels and stories |

#### 🧘 Focus mode

| Option | What it does |
|---|---|
| Turn off the feed, stories, Reels, Explore or comments | One by one. Reels opened from chats can stay allowed |
| 🆕 Limit reels per day | 1 to 10000 a day (100 by default), with warnings when 10 and 5 are left |
| 🆕 Block reels at night | From 22:00 to 07:00 by default, or the hours you set |
| ✨ Remove Meta AI | From search, chats, Reels and the post and reel ⋮ menus |
| ✨ Skip contact and location prompts | Also declines Instagram's location requests without asking |
| Hide Instagram Plus offers | Hides the Plus upsell buttons and badges |
| Extreme mode | Locks your Turn off and reel limit choices. Undoing it means clearing the data of both Instagram and InstaEclipse |

Turning off Reels overrides the reel limits. A blocked reel says why and until when.

</details>

<details>
<summary><b>💬 Social</b>: messages, likes, comments and profiles</summary>

#### 💬 Messages

| Option | What it does |
|---|---|
| Keep disappearing messages | They stop deleting themselves |
| ✨ Keep unsent messages | Messages you or others unsend stay readable, in the chat and in the *Unsent messages* list. *Unsend multiple* still removes them for good |
| 🆕 Mark unsent messages | Kept unsent messages show in red |
| ✨ Keep earlier versions of edits | Tap *Edited* to see them |
| ✨ Jump to first message | Scrolls a chat back to its first message |
| Export chat | Saves a chat as a readable HTML page with replies, reactions, edit history and optional media |
| Translate messages | Long-press a message to send it to your translator app |
| Unsend multiple messages | Long-press your message › *Unsend multiple*, tick the ones you want and unsend them at once |
| Ask before calling | Calls never start by accident |
| 🆕 Hide the InstaEclipse chat button | Triple-tap between the chat name and the call buttons to open the chat menu instead |
| Hide Notes | Removes Notes from the top of your inbox |
| Hide suggested accounts | Below your chats |
| Hide Blend | In chat headers |
| ✨ Hide "Create group" when sharing | Gone from the share sheet, without flashing on screen first |
| Hide Instants | Removes the stack of Instants floating over the app |
| Send Instants from your gallery | Send a gallery photo as an Instant |

#### ❤️ Likes & comments

| Option | What it does |
|---|---|
| Don't like on double-tap | In posts, comments and chats |
| ✨ Wait before sending likes | The heart stays blue for 4 seconds; tap again to take the like back |
| Ask before liking | A like is sent only after you confirm it |
| ✨ Hide the Repost button | On posts and reels, and reposting is blocked |
| ✨ Copy comments | Long-press a comment; its GIF or image can be saved too |
| ✨ Search comments | Search, load every comment and export them |
| ✨ Copy captions | Hold a caption to copy it |

#### 👤 Profiles

| Option | What it does |
|---|---|
| ✨ Show follow status on profiles | *Follows you*, *Doesn't follow you* or *You follow each other* |
| Show follow status pop-up | A short message when you open a profile |
| 🆕 Show who follows you back | In your own Following list |
| ✨ Profile analyzer | Who an account followed or unfollowed, and who followed or left it, since the last check, with a dated changelog |
| Export follower lists to CSV | From the tools icon on a followers or following list |
| ✨ Copy username and bio, download all | Hold the username or bio to copy it; ⋮ › *Download all posts* |
| Saved button on your profile | Your Saved posts one tap away |
| Hide Threads on profiles | Removes the Threads button |
| Use fancy fonts in your bio | Bold, italic, script, monospace and more, from a font button in Edit profile |
| Ask before following | A follow is sent only after you confirm it |
| Change how your profile looks | Name, username, bio, photo, counts and a verified badge, with a live preview. Only you see it |
| Change counts on your posts | Likes, comments, reposts and shares on your own posts. Only you see them |

</details>

<details>
<summary><b>🎨 Appearance</b>: theme and navigation</summary>

#### 🎨 Custom theme

| Option | What it does |
|---|---|
| Use a custom theme | Presets or your own colours for every part of Instagram, applied live, with separate picks for dark and light mode |
| Custom font | Any .ttf or .otf across the app |
| Custom emoji | Swap in another emoji font |

#### 🧭 Navigation

| Option | What it does |
|---|---|
| Hide Home, Reels, Create, Messages, Activity or Profile tab | One switch each |
| Tab order | Put the bottom tabs in any order |
| Startup tab | The tab Instagram opens on |
| Turn off swipe to camera | Swiping sideways on the feed no longer opens the camera |
| Back on Home leaves Instagram | Instead of jumping to the top of the feed |

</details>

<details>
<summary><b>🛠️ Tools</b>: app, backups and developer options</summary>

<br/>

| Option | What it does |
|---|---|
| Allow more than 5 accounts | Add more accounts than Instagram allows |
| Block the forced-update lockout | Shown when Instagram's servers say this version is too old |
| Fix missing notifications | For Android 12 and newer |
| 🆕 Unlock Plus features | Only on this phone; your account doesn't get Plus |
| Show what loaded at start | A short card at start: how many features are active and which aren't working |
| Auto-clear cache when large | Clears Instagram's cache, never your data, when you leave the app |
| Rescan Instagram | Use it when features stop working |
| Back up and restore settings | All your settings in one file |
| Back up and restore data | Unsent messages, edit history, hidden chats, read receipt chats, edited post counts, analyzed profiles and followers history in one file |
| ✨ Restart now | Restarts Instagram; *Clear Instagram's cache and restart* is its own button |
| Instagram developer options | Meta's internal settings, with config import, export and *Restore to default config* |

</details>

<details>
<summary><b>📱 Companion app</b></summary>

<br/>

| Part | What it does |
|---|---|
| Home | Whether InstaEclipse really ran in Instagram, your Instagram version, quick launch and restart, pinned shortcuts, and a setup guide for new users |
| Features | Every option, with search. Changes apply in Instagram right away |
| Logs | Hook status from Instagram and the app, no adb needed. 🆕 How Instagram's earlier sessions ended, and crash reports to share or delete |
| Help | Answers to common problems, setup links for Vector and LSPatch, and the community |
| Updates | Checks this fork's releases at most once a day, shows what's new and lets you skip a version |
| 18 languages | English, Arabic, Azerbaijani, Chinese (Simplified and Traditional), French, German, Greek, Hebrew, Indonesian, Italian, Polish, Portuguese, Russian, Spanish, Swedish, Turkish and Vietnamese, on every screen |

</details>

---

## Compatibility

| | |
|---|---|
| **Instagram** | `451.0.0.20.80` only, [from APKMirror](https://www.apkmirror.com/apk/instagram/instagram-instagram/instagram-451-0-0-20-80-release/). On other builds the module still loads, but some features may not work |
| **Android** | 9 and newer. A few extras need Android 11 |
| **Frameworks** | The new Xposed API (102): [JingMatrix LSPatch](https://github.com/JingMatrix/LSPatch) 1.0 or newer (no root, tested with this fork in local patch mode) or [Vector](https://github.com/JingMatrix/Vector) 2.2 or newer (root, not tested with this fork) |
| **Releases** | Numbered after the upstream version plus a fork counter (`0.8.2-lumix.1`, `0.8.2-lumix.2`, …) and signed with the fork's own key, whose fingerprint is in the release notes |

---

## FAQ

**A feature isn't doing anything.**
Open the menu and tap the chip at the top: it lists features that couldn't attach or that need a restart. Try *Rescan Instagram* there. If that doesn't help, disable and enable the module in LSPatch or Vector, then force stop and reopen Instagram.

**I updated InstaEclipse, but Instagram still runs the old version (LSPatch).**
If the module is embedded in the patched Instagram, patch Instagram again with the new APK. Updating the companion app alone leaves the old module inside Instagram.

**The text in the InstaEclipse menu is missing.**
Turn off Hide My Applist for Instagram. InstaEclipse reads its text from its own app, and HMA hides it.

**Some features won't work even when they are on.**
Instagram's own config can block them. Open *Developer options* and choose *Restore to default config*.

**I turned on Focus mode, but the content is still there.**
Use *Clear Instagram's cache and restart* (Tools › Restart Instagram).

**Where do updates come from?**
The app checks this fork's [releases](https://github.com/LumixCode/InstaEclipse/releases). Releases from the official InstaEclipse can't be installed over this fork.

---

## Building from source

You need JDK 17 and the Android SDK (compile SDK 34). Gradle comes with the repository.

```bash
git clone https://github.com/LumixCode/InstaEclipse.git
cd InstaEclipse
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Point Gradle at your SDK with `ANDROID_HOME` or a `local.properties` file containing `sdk.dir=...`. On Windows, use `gradlew.bat`.

<details>
<summary><b>Release builds</b></summary>

<br/>

`./gradlew assembleRelease` builds a shrunk APK (R8 and resource shrinking). It signs with your own key when a `keystore.properties` file with `storeFile`, `storePassword`, `keyAlias` and `keyPassword` sits in the project root; git ignores that file. Without it, the release APK is signed with the debug key, so it still installs over a debug build from the same machine.

The published releases come from the [Release workflow](.github/workflows/release.yml). It works out the next version, builds the APK, signs it with the fork's key from the repository secrets, checks the signature and version, and publishes the release with notes grouped from the commit messages. From a branch other than `main` it only makes a pre-release or a dry run.

</details>

---

## Credits

This fork exists thanks to [ReSo7200](https://github.com/ReSo7200), who created and maintains InstaEclipse, and to [everyone who contributed to it](https://github.com/ReSo7200/InstaEclipse/graphs/contributors), including the translators. News and help for the original project: [Telegram channel](https://t.me/InstaEclipse) and [Telegram group](https://t.me/instaEclipse_discussion).

Built with [JingMatrix/Vector](https://github.com/JingMatrix/Vector), the Xposed framework it runs on, [JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch) for phones without root, and [LuckyPray/DexKit](https://github.com/LuckyPray/DexKit), which finds Instagram's code at runtime.

## License

InstaEclipse is licensed under the [GNU General Public License v3.0](LICENSE), and so is this fork. It is a modified version of the original: every change is listed, with its date, in this repository's commit history (from October 2026).

---

<div align="center">
  <sub>Not affiliated with Meta or Instagram. See the <a href="DISCLAIMER.md">Disclaimer</a>.</sub>
</div>
