<div align="center">
  <img src="assets/logo.png" alt="InstaEclipse" width="120" />
  <h1>InstaEclipse · LumixCode fork</h1>
  <p><b>Instagram, the way you want it, with a few more switches.</b><br/>
  A fork of <a href="https://github.com/ReSo7200/InstaEclipse">ReSo7200/InstaEclipse</a>, the Vector (Xposed) module for privacy, downloads, a cleaner feed, smarter stories and DMs, and your own look. This fork keeps everything upstream has and adds its own features and fixes on top.</p>

  <p>
    <a href="https://github.com/LumixCode/InstaEclipse/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/LumixCode/InstaEclipse?include_prereleases&style=for-the-badge&logo=github&color=1a1a2e&labelColor=0d0d0d"/></a>
    <a href="https://github.com/ReSo7200/InstaEclipse/releases/tag/v0.8.2"><img alt="Based on InstaEclipse 0.8.2" src="https://img.shields.io/badge/based%20on-InstaEclipse%200.8.2-1a1a2e?style=for-the-badge&logo=github&labelColor=0d0d0d"/></a>
    <a href="#compatibility"><img alt="Instagram 451" src="https://img.shields.io/badge/Instagram-451.0.0.20.80-E4405F?style=for-the-badge&logo=instagram&labelColor=0d0d0d"/></a>
    <a href="#compatibility"><img alt="Xposed API 102" src="https://img.shields.io/badge/Xposed%20API-102-1a1a2e?style=for-the-badge&labelColor=0d0d0d"/></a>
    <a href="LICENSE"><img alt="License GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-1a1a2e?style=for-the-badge&labelColor=0d0d0d"/></a>
  </p>

  <p>
    <a href="#what-this-fork-adds">What this fork adds</a> •
    <a href="#features">Features</a> •
    <a href="#compatibility">Compatibility</a> •
    <a href="#installation">Installation</a> •
    <a href="#building-from-source">Building</a> •
    <a href="#faq">FAQ</a> •
    <a href="#credits">Credits</a>
  </p>
</div>

---

## About

InstaEclipse is a module for [Vector](https://github.com/JingMatrix/Vector), the Xposed framework formerly known as LSPosed (rooted phones), and [LSPatch](https://github.com/JingMatrix/LSPatch) (no root). Long press the Explore (search) tab inside Instagram and the InstaEclipse menu slides up. Flip what you want, and most things apply right away. A companion app shows whether the module is running, its logs, settings backups, themes and help.

Under the hood it uses [DexKit](https://github.com/LuckyPray/DexKit) to find what it needs inside Instagram at runtime, instead of relying on names that change with every Instagram build.

**About this fork.** It starts from upstream InstaEclipse 0.8.2 (commit [`e19ab0d`](https://github.com/ReSo7200/InstaEclipse/commit/e19ab0def2dc1ac197b7a2d4f8763f701ef68b1d)). Every change on top is its own commit: one per feature, fix or improvement, so you can read, review or cherry-pick each one. See the [commit history](https://github.com/LumixCode/InstaEclipse/commits/main) or the [full comparison with upstream](https://github.com/ReSo7200/InstaEclipse/compare/main...LumixCode:InstaEclipse:main).

> [!IMPORTANT]
> This is an unofficial fork. Please don't report problems with this fork's features to the upstream project. InstaEclipse is a personal, educational project and is not affiliated with Meta or Instagram. See the [Disclaimer](DISCLAIMER.md).

---

## What this fork adds

What you get here that upstream InstaEclipse 0.8.2 doesn't have, or does differently.

### New features

| Feature | Upstream 0.8.2 | This fork |
|---|---|---|
| Unsent indicators | ❌ A kept unsent message looks like any other | ✅ Kept unsent messages show in red: a red bubble for text, a red fill for voice messages, a red outline for other media |
| Unsent media details | ❌ Unsent photos, reels and voice messages aren't logged | ✅ Listed by what they were, like *Reel by @user* or *Voice message · 0:29* |
| Daily reel limit | ❌ | ✅ A daily allowance of reels (1 to 10000, 100 by default) with today's count in the menu and warnings at 10 and 5 left |
| Nighttime reel block | ❌ | ✅ No reels during quiet hours (22:00 to 07:00 by default), optionally still allowing reels sent in DMs |
| Lock specific chats | ❌ Locks the whole inbox or the whole app | ✅ Lock only the chats you pick, behind a full-screen passcode screen that screenshots can't capture |
| Follow status in your Following list | ❌ | ✅ See who follows you back in your own Following list |
| Unlock Plus benefits | ❌ Only hides the Plus upsells | ✅ Turns on the Instagram Plus benefits that Instagram checks on your phone (local only, not a subscription) |
| Crash history | ❌ | ✅ The log shows how Instagram's previous sessions ended (crash, ANR, low memory), and the companion keeps copies of the last crash dumps on your phone |

### Reworked features

| Feature | Upstream 0.8.2 | This fork |
|---|---|---|
| Keep unsent messages | Logs unsent text. Keeping it in the chat is opt-in, for received messages only, and lasts until Instagram restarts | The message stays in the chat, your own unsends included, also after Instagram restarts, even past the ten newest messages Instagram keeps per chat. Logged per account and chat |
| Hide specific chats | Filters hidden chats out of the inbox; manage them in a list of hidden chats | Hiding applies at once. Hide chats from the chat menu or from a list of all your chats, and see only the hidden ones with a triple tap in the inbox header's top-left corner and your passcode |
| Chat menu button | Always in the chat header | Can be hidden: a triple tap on the empty header space opens the same menu. A switch decides whether *Unsent messages* is in it |
| Jump to first message | Stops only when you tap its entry again | Also stops as soon as you scroll the chat yourself |
| Mark a story as seen | Replays a blocked view request; its menu row is missing on some Instagram builds | Its own switch: the story menu sends the view for that one story, exactly once, while every other view stays hidden |
| Story cache | Fixed 24 hours | 1 to 720 hours, 24 by default |
| Exact dates | Rewrites every "2h ago" app-wide, inbox included | Exact dates on posts, comments and story headers in the format you pick, seconds on DM swipe times, and the inbox keeps Instagram's short times |
| Copy captions | *Copy caption* row in the post ⋮ menu | Hold a caption (feed, reels or the top of the comment sheet) to copy it |
| Comment menu | InstaEclipse's own dialog | *Copy comment*, *Download GIF* and *Download image* rows right in Instagram's long press menu, saving the comment's own media, never a linked post's |
| Comment search | A grey "Search comments" pill | Instagram's own search icon in the comment sheet's sort row |
| Follow status label | "Follows you" / "Doesn't follow you" after the name | A native label with a mutual state: they follow you, you follow each other, or they don't follow you back |
| Disable repost | Blocks the repost action, buttons stay | Also removes the repost buttons and counts from posts and reels |
| Hide onboarding prompts | Skips the setup screens it knows by id | Also blocks every route to the contacts and location setup pages, and silently declines location prompts while Instagram has no location access |

### Fixes to upstream features

<details>
<summary><b>Show the fixes</b> (each is its own commit)</summary>

<br/>

- **Downloads:** each carousel slide saves its own video, never the post's music; audio and GIFs saved without the companion keep their real format; Giphy-hosted GIFs save to your folder; a story opened from a post saves the story, not the post; downloads stay at the best quality while Force Reels quality is on
- **Voice messages, stickers and GIFs:** downloading from the long press menu saves only the message you pressed, and Instagram's own Copy keeps working
- **Instants:** a gallery photo picked for an Instant is dropped when you leave the camera
- **Ghost mode:** Hide view once opened and Keep disappearing messages no longer leave later messages stuck on *Sending*; the mark-as-read icon shows under embedded LSPatch
- **Chat export:** long exports the companion can't take are saved inside Instagram instead of failing (comment exports too)
- **Lock:** an empty passcode counts as a wrong try instead of crashing; turning off Lock saved collections needs the passcode, and while a passcode is set the companion app can turn locks on but not off
- **Stories:** exact dates in the story header wrap instead of being cut off; the Story Info logo loads from the module
- **Reels:** hold for slow motion slows only the reel you are holding
- **Distraction Free:** turning a switch off brings the hidden tab, tray or button back right away; Disable Explore also hides search's top results on current Instagram builds
- **Links:** shared links lose every tracking parameter, even renamed ones, while login and app links keep theirs; links in Instagram's in-app browser sheets also open in your browser; custom share domains accept embed mirrors such as ddinstagram.com
- **Feed:** hiding suggested and liked posts finds the right feed parser on Instagram 451
- **Remove Meta AI:** leaves the rest of Reels and search intact, and also clears Meta AI rows from the post and reel ⋮ menus
- **Share sheet:** Hide "Create group" never lets it flash on screen, and the app row is evenly spaced
- **Location spoofing:** nearby Wi-Fi, cell towers and Bluetooth are hidden from Instagram too, so they can't give your real location away
- **Stability:** Ask before liking and Edit counts no longer risk crashing Instagram while they are off; several hooks are installed only while their feature is on; fewer leaks of views and profiles; the DexKit cache is rebuilt when the module changes; hooks can cancel a call on the new Xposed API
- **Themes and fonts:** theme changes apply live with a single refresh; Instagram's greys map to the right theme slots; each picked font is written to a new file
- **Companion app and backups:** settings changed in the companion while Instagram was closed apply on its next start, also under embedded LSPatch; settings backups keep every setting, number and text ones included; restoring one never turns a passcode lock off, never brings back a cleared unsent message and never unhides or renames a chat; settings saved by older versions load without errors
- **Security:** other apps can't pose as the companion app to change settings or restore data, and the companion can't turn Extreme Mode or a passcode lock off behind Instagram's back
- **Updates:** no error popup when offline

</details>

### Not in this fork

- The **Legacy build** (`-L`) for the classic Xposed API. This fork builds only for the new API (102).
- **Translate captions and comments.** They lived in the caption ⋮ menu and the comment dialog, which hold-to-copy and the native comment menu rows replace. Chat messages can still be sent to your translator app.
- The **eye and trash icons** in the chat header from upstream's chat controls. Their actions are in the chat menu.

---

## Features

The full list, including everything from upstream. Everything here is a switch in the menu. Tap a section to open it. 🆕 marks what is new in this fork, ✨ what works differently than upstream.

<details>
<summary><b>👻 Ghost Mode</b>: browse without leaving footprints</summary>

<br/>

| Feature | What it does |
|---|---|
| Hide DM seen | Read messages without sending a read receipt |
| Read receipts for specific chats | Keep Hide DM seen on for everyone, but still send "Seen" in the chats you pick (set it from the chat's InstaEclipse menu) |
| Hide typing indicator | Type without the other person seeing the dots |
| Hide story views | Watch stories without appearing in the viewer list |
| ✨ Mark a story as seen | With Hide story views on, send the view for one story on purpose from its menu (its own switch) |
| Hide live presence | Join lives without being counted |
| Bypass screenshot detection | Screenshot disappearing media without the other person being told |
| Hide view once opened | Open view once media without marking it as opened |
| Spoof last seen | Freeze your "active" status |
| Airplane mode | Appear offline: Instagram stops its live connection while you browse |
| Quick toggle | Turn Ghost Mode on and off from a ghost icon next to your inbox |

</details>

<details>
<summary><b>💬 Messages</b>: keep what matters in your chats</summary>

<br/>

| Feature | What it does |
|---|---|
| Keep disappearing messages | Stop ephemeral messages from deleting themselves |
| ✨ Keep unsent messages | When someone unsends a message, it stays in the chat (also after Instagram restarts) and a private copy is logged per account and chat. Turn off "keep in the chat" to only log it |
| 🆕 Unsent indicators | Kept unsent messages show in red: a red bubble for text, a red fill for voice messages, a red outline for other media |
| 🆕 Unsent media details | Unsent photos, reels, voice messages and links are listed by what they were, like *Reel by @user* or *Voice message · 0:29* |
| Edit history | See every earlier version of a message that was edited |
| Chat export | Export a chat to a readable HTML page with replies, reactions, edit history and optional media |
| ✨ Jump to first message | Scroll a chat all the way back to its first message; scroll the chat yourself to stop |
| Ask before calling | Tapping a chat's audio or video call button asks first, so calls never start by accident |
| Allow screenshots in DMs | Take screenshots where Instagram blocks them |
| Unlock view once and view twice | Replay view once media instead of it disappearing |
| ✨ Voice message downloads | Save the voice message you pressed, from the top of its long press menu, named by sender |
| ✨ Sticker and GIF downloads | Save the sticker or GIF you pressed in its original format, named by sender |
| DM photos and videos | Instagram's Save on a photo or video in a chat goes through InstaEclipse: named after the sender, in their folder, at the best quality |
| Translate messages | Send a message to your translator app from its long press menu (under More) |
| Unsend multiple messages | Long press one of your messages, then More, Unsend multiple: check your messages right in the chat and unsend them all at once |
| ✨ Hide specific chats | Hide chats from the chat menu or from a list of your chats; triple-tap the inbox header's top-left corner and enter your passcode to see only the hidden ones |
| Hide the Notes tray | Remove Notes from the top of your inbox |
| Hide suggested accounts | Remove the "Suggested for you" accounts under your chats |
| Hide Blend in chats | Remove the Blend button from the chat header |
| ✨ Hide "Create group" | Remove it from the share sheet, without it flashing on screen first |

All chat tools live in one InstaEclipse button in the chat header, next to the back arrow. ✨ You can hide that button and open the same menu by triple-tapping the empty space between the chat name and the call buttons, and choose whether *Unsent messages* shows in it.

</details>

<details>
<summary><b>📖 Stories</b>: see everything a story holds</summary>

<br/>

| Feature | What it does |
|---|---|
| Story Info | Exact posted, expiry and edit times, music, location, hashtags, reshared from, original post or reel, posting app, alt text, size, countdowns, questions, Add Yours (exact joined count and top people), paid partnership and collaborators. Tap any line to copy it |
| Music links | Open the song in Spotify, YouTube Music or Apple Music, from Story Info, or from Media info on posts and reels |
| Poll, slider and quiz results | Results drawn as bars, your own answer marked, and the correct quiz answer in green |
| Copy story links | Copy the links from link stickers, or pick from a list when there are several |
| Story tray | Make the story tray smaller so more stories fit in a row, and color the ring around unseen stories with a real color picker: Instagram's colors, one color or a gradient of 2 to 4 colors, with presets, sliders and hex |
| Hide the story tray | Keep the feed, lose the story row |
| View story mentions | See every @mention at once. Tap to copy, hold to open the profile |
| Search your story viewers | A search box over your viewer list |
| Disable story auto swipe | Stories stop advancing on their own |
| Download a whole highlight | From the highlight's long press menu |

</details>

<details>
<summary><b>🎬 Reels</b>: watch on your terms</summary>

<br/>

| Feature | What it does |
|---|---|
| Playback speed | 0.5x to 3x for reels, stories and feed videos |
| Hold for slow motion | Hold the left side of a reel for 0.5x, the right side for 2x |
| Always show the seek bar | Scrub through any reel |
| Reels auto scroll | Move to the next reel when one ends |
| Lock Reels scrolling | Stop accidental swipes |

</details>

<details>
<summary><b>📥 Downloads and media</b>: save and share anything</summary>

<br/>

| Feature | What it does |
|---|---|
| Download posts, reels and stories | Single photos, full carousels, reels, and stories (your own included, with music), always at the best quality available. A carousel offers the slide on screen, all slides, or a pick |
| Download a whole profile | Download all of a profile's posts, or just the newest 12, 30, 50, 100 or any number, from its ⋮ menu |
| Save video frame | Step through a video one frame at a time and save the frame as a full-resolution PNG, in true colors |
| Download profile pictures | Long press a profile picture to see the full-size original, square, and save it |
| Audio only | Save just the audio of a video or reel as .m4a, including a post's music |
| Copy media link | Copy the direct link for a post, reel or a single carousel slide |
| Copy image | Copy a photo straight to the clipboard |
| Media info | Resolution, file size, the song with streaming links, and an option to open the file in another app |
| Download with InstaEclipse | Share a post or reel link from any app to download it |
| ✨ Story cache | Keep stories you watched for 1 to 720 hours (24 by default), so you can save ones that expired |
| Save Instants | Long press an Instant to save it |
| Upload Instant from gallery | Send a picture from your gallery as an Instant |
| Download folder | Choose the folder, sort into a folder per account, and add the date to file names. If the InstaEclipse app can't be reached (asleep or not allowed to autostart), downloads still save: to your folder when it's inside Download, otherwise to Download |

</details>

<details>
<summary><b>🎞️ Quality</b>: the best Instagram has</summary>

<br/>

| Feature | What it does |
|---|---|
| Force Reels quality | Pick a fixed quality (Auto, 360p to 1080p, or Max) |
| High resolution images | Load the sharpest version of every photo |
| Max upload quality | Always upload at the highest quality |
| Spoof device | Instagram sees a Pixel 11 Pro XL or Galaxy S26 Ultra, which unlocks HDR video downloads and other features it limits to those phones. Adds the matching developer options |

</details>

<details>
<summary><b>✨ Feed</b>: see the people you actually follow</summary>

<br/>

| Feature | What it does |
|---|---|
| Following only feed | Show only posts from accounts you follow |
| Hide suggestions | Remove suggested posts, reels and accounts |
| Hide Threads suggestions | Drop the Threads promos |
| Hide posts you already liked | Skip what you have seen |
| Hide community notes | Remove notes added under posts and reels |
| Disable Discover People | Remove the "people you may know" row |
| Always show full captions | No more "... more" |
| ✨ Exact dates | "17 Oct 2026 07:36" instead of "2h ago" on posts, comments and story headers, in the date format you pick (presets or your own pattern). DM swipe times show seconds; the inbox keeps Instagram's short times |
| Photo zoom | Long press a photo to zoom in |
| Rounded posts | Round the corners of photos and videos in the feed (8 to 24 dp) |
| Ask before refreshing | Pulling the home feed down asks before it reloads, so an accidental pull doesn't lose your place |
| Disable video autoplay | Videos wait for a tap |

</details>

<details>
<summary><b>❤️ Likes, comments and profiles</b></summary>

<br/>

| Feature | What it does |
|---|---|
| Disable double tap to like | No accidental likes, in posts, comments and DMs |
| Undo a like | A few seconds to take a like back |
| Edit counts on your posts | Change the likes, comments, reposts and shares shown on your own posts from their ⋮ menu; shown only on your phone |
| Ask before liking | A like is sent only after you confirm it, and the heart stays empty until you do |
| Ask before following | A follow is sent only after you confirm it |
| ✨ Disable repost | Remove the repost buttons and counts from posts and reels, and block reposts |
| ✨ Comment search | Search a post's comments from Instagram's own search icon in the sort row, load them all (on Reels the sheet opens to full height so every comment loads), and export them |
| ✨ Copy comments and captions | Copy a comment from its long press menu; hold a caption (feed, reels or the top of the comment sheet) to copy it |
| ✨ Save comment GIFs and images | Download a comment's own GIF or image from its long press menu |
| Profile menu tools | Copy a profile's username or bio, or download its posts (all, or the newest ones), from its ⋮ menu |
| Profile spoofer | Change how your own profile looks to you: name, username, bio, profile picture, post / follower / following counts and a verified badge, with a live preview editor; shown only on your own profile on your phone, nothing is sent to Instagram |
| Profile analyzer | Put a profile on your watchlist and analyze it from its ⋮ menu: its Followers and Following are loaded and compared with the last analysis, showing who it newly followed or unfollowed and who followed or stopped following it, with a dated change log per profile |
| ✨ Follow status label | See on any profile whether they follow you, you follow each other, or they don't follow you back |
| 🆕 Follow status in your following list | See who follows you back in your own Following list |
| Fancy bio fonts | The bio's "Change font" button picks a style for what you type next: bold, italic, script, monospace and more; strikethrough and underline work in any language |
| Follower toast | A quick message when you open a profile |
| Export followers and following | Save the whole list as CSV |
| Followers history | See who unfollowed you and who followed you since your last check |
| Saved on your profile | A Saved button right on your profile |
| Lock Saved collections | Put a passcode on a collection |
| Hide the Threads button | Remove it from profiles |

</details>

<details>
<summary><b>🧭 Navigation</b>: arrange Instagram's tabs</summary>

<br/>

| Feature | What it does |
|---|---|
| Hide tabs | Hide Home, Reels, Create, Messages, Activity or Profile |
| Tab order | Put the bottom tabs in any order |
| Startup tab | Choose which tab Instagram opens on |
| Disable swipe to camera | Stop the camera from opening on a swipe |
| Back leaves Home | On the home feed, Back leaves Instagram instead of scrolling to the top |

</details>

<details>
<summary><b>🔒 Privacy and security</b></summary>

<br/>

| Feature | What it does |
|---|---|
| Lock the whole app | Passcode or fingerprint to open Instagram |
| Lock DMs | Lock just the inbox, and optionally lock it again every time you leave |
| 🆕 Lock specific chats | Lock only the chats you pick, behind a full-screen passcode screen that screenshots can't capture |
| Block ads and analytics | Drop sponsored posts and tracking calls |
| ✨ Disable tracking links | Strip every tracking parameter from links you share, even renamed ones, while login and app links keep theirs |
| Don't save searches | Profiles, tags and places you open from Search stay out of Recent, on the phone and on Instagram's server |
| ✨ Location spoofing | Pick any place on a map; Instagram sees that location everywhere, and nearby Wi-Fi, cell towers and Bluetooth are hidden so they can't give your real location away |

</details>

<details>
<summary><b>🎨 Appearance</b>: make it yours</summary>

<br/>

| Feature | What it does |
|---|---|
| Custom theme | Presets or your own colors for every part of the app, applied live |
| Light and dark themes | Every theme has both versions and follows your phone |
| Themed menus | InstaEclipse's own menus, toggles and dialogs use your theme's accent color |
| Custom font | Use any .ttf or .otf across the app |
| Custom emoji | Swap in another emoji font |

</details>

<details>
<summary><b>🧘 Distraction Free</b>: quiet the parts that pull you in</summary>

<br/>

| Feature | What it does |
|---|---|
| Turn off sections | Stories, Feed, Reels (or all Reels except in DMs), Explore and Comments, one by one |
| 🆕 Daily reel limit | A daily allowance of reels (100 by default, 1 to 10000) with today's count in the menu and warnings at 10 and 5 left; optionally still open reels sent in DMs |
| 🆕 Nighttime reel block | Block reels during quiet hours (22:00 to 07:00 by default), optionally except reels sent in DMs |
| Extreme mode | Lock those choices in until you reinstall |
| ✨ Remove Meta AI | Take Meta AI out of search, chats, reels and the post and reel ⋮ menus |
| ✨ Hide onboarding prompts | Skip the nag screens, including the contacts and location "Set up on new device" pages. While Instagram has no location access, its location requests are answered as denied, without a popup |
| Hide Instagram Plus upsells | No more Plus prompts |
| Hide Instants | Remove the stack of Instants floating over the app |

Disable Reels overrides the daily limit and the nighttime block.

</details>

<details>
<summary><b>🛠️ App, accounts and tools</b></summary>

<br/>

| Feature | What it does |
|---|---|
| Developer Options | Instagram's hidden internal panel, with config import, export and a known good default |
| Unlimited accounts | Add more accounts than Instagram allows |
| 🆕 Unlock Plus benefits | Turn on the Instagram Plus benefits that Instagram checks on your phone (a local eligibility override, not a subscription) |
| ✨ Open links externally | Use your own browser instead of Instagram's, also for links in its in-app browser sheets (Facebook and Threads links stay in Instagram) |
| Custom share domain | Share links with a different domain |
| Notification fix | Repair notifications that stopped arriving |
| Auto clear cache | Clear Instagram's media cache once it gets too big |
| Backup and restore | All settings to a file and back |
| Back up data | Deleted messages, analyzed profiles, followers history, hidden chats and edited post counts in one file, restored in one tap after reinstalling |
| Feature toasts | See which features loaded when Instagram starts |

</details>

<details>
<summary><b>📱 Companion app</b></summary>

<br/>

| Feature | What it does |
|---|---|
| Home dashboard | Module status, Instagram version, quick launch and restart, and your pinned shortcuts |
| Feature search | Find any switch by name |
| Instant sync | Switches flipped in the app apply in Instagram right away, and the two menus stay in step |
| Setup checks | Asks to let the app run in the background when you choose a download folder, and to choose the folder again if its access was lost |
| ✨ Live logs | Hook status from Instagram and the app, no adb needed, plus how Instagram's previous sessions ended (crash, ANR, low memory) |
| Help | Frequently asked questions, setup links for Vector and LSPatch, and the community |
| 18 languages | English, Arabic, Azerbaijani, Chinese (Simplified and Traditional), French, German, Greek, Hebrew, Indonesian, Italian, Polish, Portuguese, Russian, Spanish, Swedish, Turkish and Vietnamese. Text added by this fork is English only for now |

</details>

---

## Compatibility

| | |
|---|---|
| **Tested on** | Instagram `451.0.0.20.80` with JingMatrix LSPatch in local patch mode |
| **Get this version** | [Instagram 451.0.0.20.80 on APKMirror](https://www.apkmirror.com/apk/instagram/instagram-instagram/instagram-451-0-0-20-80-release/) |
| **Android** | 9 and newer |
| **Frameworks** | The new Xposed API (102): [JingMatrix LSPatch](https://github.com/JingMatrix/LSPatch) 1.0 or newer (no root), or [Vector](https://github.com/JingMatrix/Vector) 2.2 or newer (root; supported upstream, not tested with this fork) |
| **Survives Instagram updates** | Mostly: DexKit finds what it needs at runtime, but a big Instagram update can still knock a feature out |

The menu inside Instagram shows how many features are active (for example "62/62 active"). Tap it to see which ones could not attach.

---

## Installation

Download the latest `InstaEclipse-<version>.apk` from [**Releases**](https://github.com/LumixCode/InstaEclipse/releases/latest), or [build it yourself](#building-from-source). Then follow the path for your phone.

Releases are numbered after the upstream version they're based on, plus a fork counter: `0.8.2-lumix.1` is this fork's first release on upstream 0.8.2, `0.8.2-lumix.2` the next. Every release is signed with the fork's own key, whose certificate fingerprint is in the release notes, so each release installs over the previous one.

> [!WARNING]
> This fork uses the same package name as the official InstaEclipse but a different signing key, so Android won't install it over the official app or over a build you signed yourself. Back up your settings in the companion app, uninstall it, install this one and restore the backup.

> Get Instagram [451.0.0.20.80 from APKMirror](https://www.apkmirror.com/apk/instagram/instagram-instagram/instagram-451-0-0-20-80-release/) rather than the Play Store. The Play build is not always fully supported.

### No root, with LSPatch

1. **Install InstaEclipse** from the APK.
2. **Install [JingMatrix's LSPatch](https://github.com/JingMatrix/LSPatch/releases/latest)** 1.0 or newer.
3. **Patch Instagram.** In LSPatch, tap **+**, pick the Instagram APK or the installed app, choose **Local Patch Mode**, turn on **Inject loader dex**, then tap **Start Patch**.
4. **Install the patched APK** and log in.
5. **Enable the module.** In LSPatch, open Manage, pick Instagram, open Modules and enable InstaEclipse.
6. **Open the menu.** Long press the Explore (search) tab.

### Rooted, with Vector

1. **Install InstaEclipse** from the APK.
2. **Enable the module.** In the [Vector](https://github.com/JingMatrix/Vector/releases/latest) manager (2.2 or newer), open Modules, enable InstaEclipse and tick Instagram.
3. **Restart Instagram.** Force stop it and open it again.
4. **Open the menu.** Long press the Explore (search) tab.

> [!CAUTION]
> Using Hide My Applist? Do not hide InstaEclipse from Instagram. Instagram needs to see it, or the menu text goes missing and features stop working.

---

## Building from source

You need JDK 17 and the Android SDK (compile SDK 34). Gradle comes with the repository.

```bash
git clone https://github.com/LumixCode/InstaEclipse.git
cd InstaEclipse
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Point Gradle at your SDK with `ANDROID_HOME` or a `local.properties` file containing `sdk.dir=...`. On Windows, use `gradlew.bat`.

Run the unit tests with `./gradlew testDebugUnitTest`.

**Release builds.** `./gradlew assembleRelease` signs with your own key when a `keystore.properties` file sits in the project root, with the keys `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. The file is ignored by git. Without it, the release APK is signed with the debug key, so it still installs over a debug build of the same machine.

The published releases come from the [Release workflow](.github/workflows/release.yml). It works out the next version, runs the unit tests, builds and signs the APK with the fork's key from the repository secrets, checks the signature and version, and publishes the release with notes grouped from the commit messages.

---

## Using it

- **Open the menu:** long press the Explore (search) tab inside Instagram.
- **Find things:** sections are grouped into Appearance, Privacy, Media, Social and Tools. Each shows how many of its switches are on.
- **Chat tools:** open any chat and tap the InstaEclipse button next to the back arrow, or triple-tap the empty header space if you hid that button.
- **Story tools:** open a story and tap its ⋯ menu for Story Info, links, mentions and results.
- **Back:** the phone's back button steps back one page at a time.

---

## FAQ

**A feature is not doing anything.**
Disable and enable the module again in LSPatch or Vector, then force stop and reopen Instagram. Some switches say they take effect after restarting Instagram.

**I updated InstaEclipse but Instagram still runs the old version (LSPatch).**
If the module is embedded in the patched Instagram, patch Instagram again with the new APK. Updating the companion app alone leaves the old module inside Instagram.

**The text in the InstaEclipse menus is missing.**
Turn off Hide My Applist (HMA) for Instagram. InstaEclipse reads its text from its own app, and HMA hides it.

**Some features will not work even when they are on.**
Instagram's own config can block them. Open Developer Options and choose Restore Default Config.

**I turned on Distraction Free but the content is still there.**
Force stop Instagram and clear its cache, then open it again.

**The app tells me about a new InstaEclipse version.**
The update check follows upstream's releases. An upstream release doesn't include this fork's changes until they are merged here.

---

## Credits

This fork exists thanks to [ReSo7200](https://github.com/ReSo7200), who created and maintains InstaEclipse, and to [everyone who contributed to it](https://github.com/ReSo7200/InstaEclipse/graphs/contributors), including the translators. Updates and help for the original project: [Telegram channel](https://t.me/InstaEclipse) and [Telegram group](https://t.me/instaEclipse_discussion).

Built with:

- [JingMatrix/Vector](https://github.com/JingMatrix/Vector), the Xposed framework it runs on
- [JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch), for phones without root
- [LuckyPray/DexKit](https://github.com/LuckyPray/DexKit), which finds Instagram's code at runtime

---

## License

InstaEclipse is licensed under the [GNU General Public License v3.0](LICENSE), and so is this fork. It is a modified version of the original: every change is listed, with its date, in this repository's commit history (from October 2026).

---

<div align="center">
  <sub>Not affiliated with Meta or Instagram. See the <a href="DISCLAIMER.md">Disclaimer</a>.</sub>
</div>
