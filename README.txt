m7u_h Android APK project

This project wraps the supplied HTML website in an Android WebView app.
It requires an Android SDK/Gradle build environment to generate an APK.

Easiest cloud build:
1. Create a GitHub repository and upload all files/folders in this ZIP.
2. Open the repository's Actions tab.
3. Select "Build m7u_h APK" and press "Run workflow" (or push to main).
4. When the workflow finishes, open the run and download the "m7u_h-apk" artifact.
5. Extract the artifact ZIP to get app-debug.apk, then transfer it to your Android phone
   and install it. Android may ask you to allow installs from that source.

Notes:
- Quran audio and gallery images use internet URLs, so internet access is needed.
- The three song files are placeholders (song1.mp3, song2.mp3, song3.mp3). Add your
  own files into app/src/main/assets and update their paths in index.html if desired.
- This is a debug APK for personal testing, not a Play Store release build.
