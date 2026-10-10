# HoneyBeat

HoneyBeat is a music player and a downloader in one Android app, styled like Cub by NeonBear.

**Tabs:** Library, Search, Options. Playing is not a tab: a mini player sits above the bottom bar whenever a song is loaded
(cover, previous / play / next). Tap it to open the full player.

- **Library**: "All music" plus one playlist per folder in `Music/Cave`. Search works across everything. Open a playlist and
  press the gear for: change / reset cover, Sync now, Auto-update, Delete playlist. Sort songs by **Title**, **Artist** or
  **Album** (album and artist sorts group the list under headers; the choice is remembered).
- **Player**: tinted from the cover, animated level bars, seek bar, shuffle / repeat, and what plays next. The share
  button (top right) makes a story-sized picture of the song and opens Instagram with it, like Spotify.
- **Search**: type a song, press the search button, press **Get** to download it (queue as many as you like).
  The small download button next to it opens the playlist popup: paste a YouTube Music, Spotify or Apple Music link.
  Pasting a link straight into the search box does the same. A green **Logged in to YouTube** pill shows when you are signed in.
  When you are signed in it also has **Import from my YouTube**, which downloads a playlist of yours with its cover and
  keeps it updated.
- **Options**: playback, YouTube account (sign in / out), playlist updates (on/off, check every 5 / 15 / 30 / 60 min,
  notifications), and download settings (parallel downloads, quality, cover art, mobile data, yt-dlp).

Needs Android 10 or newer.

## Build the APK on GitHub

1. Upload the contents of this folder (including the hidden `.github` folder) to a new GitHub repo.
2. Open the **Actions** tab. The build starts by itself, or press **Run workflow** on **Build HoneyBeat APK**.
3. When it finishes, download `HoneyBeat-apk` from the run's Artifacts, unzip it, and install `HoneyBeat.apk` on the phone.
   The APK is large (about 100 MB) because it carries yt-dlp, Python and ffmpeg.

Other ways: Android Studio (Build > Build APK(s)), or `build-apk.bat` (result in `dist\HoneyBeat.apk`).

## Notes

- Auto-update runs as a small foreground service (a quiet notification) while it is on, so Android lets it keep checking
  when the app is closed. It restarts after a reboot.
- YouTube sign-in uses an in-app page; Google sometimes refuses sign-ins in embedded browsers. yt-dlp can get an account
  rate-limited, so a spare account is safer. Sign out deletes the saved session.
- "All" parallel downloads runs up to 20 at once. Every download is a separate Python + ffmpeg process, and Android kills
  the app when there are too many.
- Playlists must be public (or you signed in). Spotify's public page only exposes about 100 tracks.
- Spotify and Apple Music songs are searched on YouTube, so the match can occasionally be a wrong version.
- If yt-dlp says a format is not available, HoneyBeat retries on its own: without cover art, with other YouTube clients,
  then with yt-dlp's own format choice.
- Instagram sharing: the picture goes through Instagram's normal share screen. To use Instagram's Add-to-Story screen
  directly, put a (free) Facebook App ID in `ShareMusic.IG_APP_ID`. Without Instagram installed, the Android share sheet opens.
- YouTube audio is lossy to begin with, so MP3 320 kbps is the highest MP3 setting but not better than the source.
- If downloads fail, press **Update yt-dlp** in Options. The red "Last error" line and the Log card in Search say why.
- Fonts: Tektur and Outfit, both under the SIL Open Font License (see `licenses/`).
- Only download music you have the right to copy.
