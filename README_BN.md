# Music Player: GitHub-এ আপলোড ও APK বিল্ড গাইড

## ১. GitHub-এ আপলোড
1. github.com-এ নতুন Repository বানান (যেমন `MusicPlayer`)।
2. এই ZIP এক্সট্র্যাক্ট করুন। ভেতরের ফাইল/ফোল্ডারগুলো (`app`, `gradle`, `.github`, `build.gradle.kts` ...) Repository-র রুটে আপলোড করুন।
   - ওয়েবে "uploading an existing file" দিয়ে ফোল্ডার ড্র্যাগ করলে `.github` ফোল্ডার লুকানো থাকায় বাদ পড়তে পারে। তাই git ব্যবহার করা ভালো:
   ```
   git init
   git add .
   git commit -m "Music Player"
   git branch -M main
   git remote add origin https://github.com/<আপনার-ইউজারনেম>/MusicPlayer.git
   git push -u origin main
   ```
3. `gradlew` বা `gradle-wrapper.jar` লাগবে না। Workflow নিজেই Gradle 8.7 সেটআপ করে।

## ২. APK ডাউনলোড (এখন শুধু একটাই APK)
1. Repository-র **Actions** ট্যাবে যান, "Build APK" workflow চলবে (প্রথমবার ৫-১০ মিনিট)।
2. সবুজ টিক এলে রান খুলুন, নিচে **Artifacts** থেকে **MusicPlayer** ডাউনলোড করুন।
3. ZIP খুলে `MusicPlayer.apk` ফোনে ইনস্টল করুন (Unknown sources অনুমতি দিতে হবে)।
4. আগে debug ও release দুইটা আলাদা অ্যাপ হয়ে যেত। এখন শুধু release বিল্ড হয়, তাই ফোনে একটাই অ্যাপ থাকবে।
5. APK সবসময় একই কী দিয়ে সাইন হয় (`app/keystore/musicplayer.jks`), তাই নতুন APK আগেরটার ওপর আপডেট হিসেবে ইনস্টল হবে।
   (আগের debug-key দিয়ে বানানো APK-র সাথে কী মেলে না, তাই প্রথমবার পুরনো অ্যাপ আনইনস্টল করে ইনস্টল করুন।)
6. বিল্ড ফেইল করলে Actions লগের লাল অংশের এরর আমাকে পাঠান, আমি ঠিক করে দেব।

## ৩. নিজের কিস্টোর দিয়ে Release সাইন (ঐচ্ছিক)
না দিলে প্রজেক্টের ভেতরের কিস্টোর ব্যবহার হবে (ব্যক্তিগত ব্যবহারে ঠিক আছে)। Play Store-এ দিলে নিজের কিস্টোর ব্যবহার করুন।
1. কিস্টোর বানান: `keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias mykey`
2. Base64: Linux/Mac: `base64 -w0 release.jks`, Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))`
3. Repository → Settings → Secrets and variables → Actions → New repository secret:
   - `KEYSTORE_BASE64`: উপরের Base64 টেক্সট
   - `KEYSTORE_PASSWORD`: কিস্টোর পাসওয়ার্ড
   - `KEY_ALIAS`: `mykey`
   - `KEY_PASSWORD`: কী পাসওয়ার্ড
4. আবার push করলে বা Actions থেকে "Run workflow" দিলে signed APK তৈরি হবে।

## ৪. v1.1.0 আপডেট
- নতুন animated splash: "Music Player / Powered By / S. Om. Yogeshananda"।
- Library-তে ডানে-বামে সোয়াইপ করলে Songs, Folders, Albums, Artists, Genres ইত্যাদি ট্যাব বদলায়।
- উপরের রিলোড বাটনে "Scanning…" দেখায়, স্টোরেজের নতুন গান খুঁজে লাইব্রেরি রিলোড করে।
- Folders, Albums, Artists, Genres নাম/তারিখ/গানের সংখ্যা অনুযায়ী সাজানো যায় এবং grid/list বদলানো যায়।
- Developer এখন নিচের বারে (Library, Search, Settings এর পাশে); Settings থেকে অপ্রয়োজনীয় অপশন সরানো হয়েছে।
- Player স্ক্রিনে ১০ সেকেন্ড পেছানো/আগানোর বাটন।
- ল্যাগ কমানো হয়েছে (settings recomposition, background animation, list animation, mini player)।
- নোটিফিকেশনে নিজস্ব আইকন; previous, play/pause, next ও close বাটন।
