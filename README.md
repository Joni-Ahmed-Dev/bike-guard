# 🏍️ BikeGuard (v1.0 — সব Phase সম্পূর্ণ)

নিজস্ব Android-ভিত্তিক বাইক GPS ট্র্যাকার। বাইকে রাখা একটি Android ফোন **Tracker**, আপনার হাতের ফোন **Viewer**। কোনো ESP32/GPS/GSM মডিউল লাগে না।

**Stack:** Kotlin · Jetpack Compose (Material 3) · MapLibre + OpenStreetMap (OpenFreeMap) · Supabase (Postgres + Auth + RLS) · Room · WorkManager · GitHub Actions

## Phase 2-তে যা যোগ হয়েছে (v0.2.0)
- **Auto মোড:** বাইক চলতে শুরু করলে (Google Activity Recognition) ট্র্যাকিং নিজে চালু হয়; ১০ মিনিট পার্ক থাকলে বন্ধ হয়
- **Adaptive battery:** চললে ৫–১০ সেকেন্ড, ২ মিনিট থামলে প্রতি ১ মিনিট, ব্যাটারি ≤১৫% (চার্জে নেই) হলে আরও ধীর
- **Trips ট্যাব:** গত ৩০ দিনের রাইড (দূরত্ব, সময়, গড়/সর্বোচ্চ গতি) — সার্ভারের SQL ফাংশন `device_trips()` থেকে
- **Trip Replay:** গতি-অনুযায়ী রঙিন রুট, প্লে/পজ, ১০×/৩০×/১০০× গতি, স্লাইডার, ফলো ক্যামেরা
- ⚠️ **নতুন SQL:** Supabase SQL Editor-এ `supabase/migrations/0002_trips.sql` চালাতে হবে

## Phase 1-এ যা আছে
- এক অ্যাপে Tracker / Viewer, ইমেইল-পাসওয়ার্ড লগইন (Supabase Auth, টোকেন Keystore-এ এনক্রিপ্টেড)
- Foreground Service + Fused Location, ৫/৭/১০ সেকেন্ড ব্যবধান
- **Offline queue:** প্রতিটি লোকেশন আগে Room-এ সেভ হয়, নেট ফিরলে batch আপলোড (ডুপ্লিকেট-নিরাপদ)
- Manual ও Always-on মোড (Always-on: রিবুটের পর চালু, Android বন্ধ করলে Watchdog আবার চালু করে)
- Viewer: ফুলস্ক্রিন ডার্ক ম্যাপ, মসৃণ চলমান মার্কার, দিকনির্দেশক, রুট ট্রেইল, bottom sheet (গতি, ব্যাটারি, শেষ আপডেট), Navigate বাটন
- ধাপে ধাপে Permission onboarding, ব্যাটারি-অপটিমাইজেশন গাইড, বাংলা + ইংরেজি (সিস্টেম ভাষা অনুযায়ী)

## সেটআপ (৩ ধাপ)

### ১. Supabase (ফ্রি)
1. [supabase.com](https://supabase.com) → New project
2. **SQL Editor** খুলে `supabase/migrations/0001_init.sql` পেস্ট করে Run
3. **Authentication → Providers → Email** থেকে *Confirm email* বন্ধ করুন (সহজ সাইন-আপের জন্য; চাইলে চালু রাখতে পারেন, তখন ইমেইল নিশ্চিত করে সাইন ইন করতে হবে)
4. **Project Settings → API** থেকে `Project URL` ও `anon public key` কপি করুন

> anon key পাবলিক হওয়ার জন্যই ডিজাইন করা; ডেটা সুরক্ষিত থাকে Row Level Security দিয়ে (প্রতিটি ইউজার শুধু নিজের ডেটা দেখে)। **service_role key কখনো অ্যাপে বা GitHub-এ দেবেন না।**

### ২. GitHub দিয়ে APK বিল্ড
1. রিপোজিটরি তৈরি করে এই ফোল্ডারের সব ফাইল push করুন
2. **Settings → Secrets and variables → Actions** এ দুটি secret যোগ করুন: `SUPABASE_URL`, `SUPABASE_ANON_KEY`
3. **Actions** ট্যাবে workflow চলবে; শেষে *Artifacts* থেকে `BikeGuard-apk` ডাউনলোড করুন
4. `v0.1.0` ট্যাগ push করলে APK অটোমেটিক GitHub Release-এ যুক্ত হবে

### ৩. লোকাল বিল্ড (ঐচ্ছিক)
```bash
cp local.properties.example local.properties   # মান বসান
gradle wrapper --gradle-version 8.10.2          # একবার, wrapper তৈরি করতে
./gradlew :app:assembleDebug
```
অথবা Android Studio (Ladybug+) দিয়ে ফোল্ডারটি খুলুন।

## ব্যবহার
1. **দুই ফোনেই** APK ইনস্টল করে **একই অ্যাকাউন্টে** সাইন ইন করুন
2. **বাইকের ফোন:** Tracker ট্যাব → পারমিশন দিন (লোকেশন → *Allow all the time*) → রিংয়ে ট্যাপ। চাইলে Settings থেকে *Always on*
3. **আপনার ফোন:** Viewer ট্যাব → বাইক নিজে থেকেই দেখা যাবে

## ⚠️ বাস্তব সীমাবদ্ধতা
- **Xiaomi/Oppo/Vivo/Samsung/Huawei:** Settings → Battery-তে অ্যাপকে *Unrestricted* করুন এবং *Autostart* চালু করুন, নইলে ফোন ব্যাকগ্রাউন্ড সার্ভিস মেরে ফেলবে
- **Auto মোড** চালু করতে "Physical activity" পারমিশন লাগে। শনাক্ত করতে সাধারণত ১–২ মিনিট দেরি হয়, তাই চলা শুরুর প্রথম অংশ রেকর্ড নাও হতে পারে। যে ফোনে পাওয়ার-সেভিং কড়া, সেখানে Always on বেশি নির্ভরযোগ্য
- Trip শনাক্ত হয় গতি ≥ ১ m/s পয়েন্ট থেকে; ২০০ মিটারের কম বা ৫ পয়েন্টের কম হলে ট্রিপ গণ্য হয় না
- বাইকের ফোন চার্জারে রাখুন; GPS-এ ব্যাটারি দ্রুত খরচ হয়
- Viewer প্রতি ৫ সেকেন্ডে সার্ভার থেকে পোল করে (লাইভ ম্যাপ খোলা থাকলে)। Realtime Phase 3-এ আসবে
- Release APK ডিফল্টে debug কী দিয়ে সাইন হয়। নিজের keystore দিতে `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` সেট করুন
- OpenFreeMap ফ্রি ও কী-ছাড়া, তবে অতিরিক্ত ট্রাফিকে নিজস্ব টাইল সার্ভার বা MapTiler ফ্রি টায়ারে যেতে হতে পারে। OSM attribution ম্যাপে দেখানো থাকে, সরাবেন না

## Phase 3 + 4-এ যা যোগ হয়েছে (v1.0.0)

**নিরাপত্তা (Phase 3)**
- **Parking mode:** পার্ক করা বাইকে অ্যাকসেলেরোমিটার + GPS পাহারা। নড়লে `motion`, ৬০ মিটারের বেশি সরলে `moved` (critical) অ্যালার্ট; তখন ১৫ মিনিট লাইভ মোড। চাইলে সাইরেন বাজে
- **Tamper alert:** GPS বন্ধ, Airplane mode, চার্জার খোলা (parking mode চালু থাকলে), ব্যাটারি কম, ফোন রিস্টার্ট, অ্যাপ জোর করে বন্ধ হয়ে আবার চালু
- **Geofence:** বাসা/অফিস ইত্যাদি; ঢুকলে/বেরোলে অ্যালার্ট (সার্ভারেই হিসাব হয়, অফলাইন ব্যাচেও ঠিক থাকে)
- **Remote command:** Viewer থেকে Locate, Live mode, Siren, Guard on/off, গতিসীমা সেট, সার্ভিস সম্পন্ন — ট্র্যাকার কনফার্ম করলে "Done" দেখায়
- **Realtime:** Viewer-এর ম্যাপ WebSocket-এ সঙ্গে সঙ্গে আপডেট হয় (না হলে ৫ সেকেন্ডের পোলিং চলে)
- **Alerts ট্যাব:** সব অ্যালার্টের তালিকা, unread ব্যাজ, ট্যাপ করলে ম্যাপে লোকেশন

**সেফটি ও সুবিধা (Phase 4)**
- **Crash detection + SOS:** জোরালো ধাক্কা + তারপর বাইক স্থির থাকলে `crash`; ৬০ সেকেন্ড "I'm OK" কাউন্টডাউন, না থামালে জরুরি নম্বরে SMS (লোকেশন লিংকসহ)। Tracker ট্যাবে **SOS** বাটন (চেপে ধরে রাখতে হয়)
- **Temporary share link:** ১/৬/২৪ ঘণ্টার লিংক; যে কেউ লগইন ছাড়াই লাইভ ম্যাপ দেখে। যেকোনো সময় বন্ধ করা যায়
- **App lock:** ফিঙ্গারপ্রিন্ট/ফেস/ফোনের PIN; লক চালু থাকলে স্ক্রিনশট ও recent-apps প্রিভিউ বন্ধ
- **Home widget + Quick Settings tile:** এক ট্যাপে ট্র্যাকিং চালু/বন্ধ
- **Speed limit alert** (Viewer বা ফোন থেকে সেট) ও **Service/Oil reminder** (কিমি অনুযায়ী ওডোমিটার)

## অতিরিক্ত সেটআপ (Phase 3/4)

### ক) Supabase SQL
SQL Editor-এ `supabase/migrations/0003_security_and_sharing.sql` চালান (০০০১, ০০০২ আগে চালানো থাকতে হবে)।

### খ) Push alert (ঐচ্ছিক, কিন্তু অ্যাপ বন্ধ থাকলে অ্যালার্ট পেতে দরকার)
1. [Firebase Console](https://console.firebase.google.com)-এ প্রজেক্ট খুলে Android অ্যাপ যোগ করুন (package: `com.bikeguard.app`), `google-services.json` নামান
2. GitHub secret `GOOGLE_SERVICES_JSON` = `base64 -w0 google-services.json`-এর আউটপুট (লোকালে ফাইলটি `app/`-এ রাখুন)
3. Firebase → Project settings → Service accounts → *Generate new private key*। তারপর:
   ```bash
   supabase secrets set FCM_SERVICE_ACCOUNT="$(cat service-account.json)"
   supabase functions deploy push
   ```
4. Supabase → Database → **Webhooks** → দুটি webhook (type: *Supabase Edge Functions* → `push`): টেবিল `events` (INSERT) এবং `commands` (INSERT)
- Firebase না দিলেও অ্যাপ চলে: অ্যালার্ট Alerts ট্যাবে আসে (অ্যাপ খোলা থাকলে), আর কমান্ড ট্র্যাকার চালু থাকলে প্রতি ১৫ সেকেন্ডে ধরা পড়ে

### গ) Share page (GitHub Pages)
1. Repo → Settings → Pages → Source: **GitHub Actions**
2. `main`-এ push করলে `pages.yml` পেজটি ডিপ্লয় করে (secret `SUPABASE_URL`, `SUPABASE_ANON_KEY` লাগে)
3. পেজের URL (যেমন `https://NAME.github.io/BikeGuard`) GitHub secret `SHARE_BASE_URL`-এ দিন (লোকালে `local.properties`-এ), তারপর APK আবার বিল্ড করুন

### ঘ) "ট্র্যাকার চুপ হয়ে গেছে" শনাক্তকরণ (ঐচ্ছিক)
Database → Extensions-এ `pg_cron` চালু করে SQL Editor-এ:
```sql
select cron.schedule('bikeguard-stale', '*/5 * * * *', 'select public.check_stale_devices()');
```
ফোন বন্ধ বা সিম খুলে ফেললে ১৫ মিনিট পর `device_stale` অ্যালার্ট আসবে।

## ⚠️ সততার সঙ্গে সীমাবদ্ধতা (পড়ে নিন)
- **এই কোড আমি বিল্ড বা ফোনে চালিয়ে পরীক্ষা করিনি।** প্রথম CI রানে ছোটখাটো কম্পাইল এরর আসতে পারে; আসল ফোনে (বিশেষ করে Xiaomi/Samsung) পরীক্ষা করে নিন
- **অফলাইন tamper:** Airplane mode বা GPS বন্ধ করলে ফোনের নেট কেটে যায়, তাই সেই অ্যালার্ট সার্ভারে পৌঁছায় নেট ফিরলে। চোর নেট বন্ধই রাখলে `device_stale` (ঘ) ছাড়া জানার উপায় নেই
- **Parking mode ম্যানুয়াল:** বাইক চালানোর আগে বন্ধ করতে ভুলবেন না (নইলে নিজেই অ্যালার্ট পাবেন)। *Arm automatically* ডিফল্টে বন্ধ, কারণ চালু থাকলে একই ঝুঁকি বাড়ে
- **Crash detection একটি অনুমান-ভিত্তিক ফিচার:** গর্তে ধাক্কা বা ফোন পড়ে যাওয়ায় ভুল অ্যালার্ট (false positive) হতে পারে, আবার কিছু দুর্ঘটনা ধরা নাও পড়তে পারে। এটা কোনো প্রত্যয়িত নিরাপত্তা-যন্ত্র নয়
- **SOS SMS** ফোনের সিম থেকে যায় (বান্ডেল/ক্যারিয়ার চার্জ প্রযোজ্য)। Google Play SMS পারমিশন সীমিত করে; GitHub/sideload APK-তে সমস্যা নেই
- **Share link:** লিংকধারী যে কেউ মেয়াদ শেষ বা বন্ধ না হওয়া পর্যন্ত লাইভ লোকেশন দেখতে পারবে; কেবল বিশ্বস্ত মানুষকে পাঠান
- Remote command ও push-wake চলতে ট্র্যাকার ফোনে লোকেশন পারমিশন (Allow all the time) ও ব্যাটারি *Unrestricted* লাগে। কমান্ড এলে ট্র্যাকিং চালু হয়ে যায়
- Geofence ম্যাপে বৃত্ত আঁকা হয় না (এখন শুধু তালিকা ও অ্যালার্ট)। Room ডেটাবেস v1→v2 আপগ্রেডে শুধু অনাপলোডেড কিউ মুছে যায়

## রোডম্যাপ
| Phase | বিষয় |
|---|---|
| 1 ✅ | Auth, Tracker service, Server sync, Live map, Offline queue |
| 2 ✅ | Auto-tracking, Trip history ও replay, Adaptive battery |
| 3 ✅ | Geofence, Parking mode, Push alerts, Tamper alert, Remote command, Realtime |
| 4 ✅ | Crash detection + SOS, Share link, App lock, Widget + Quick tile, Speed limit alert, Service reminder |
| পরে | Geofence বৃত্ত ম্যাপে, রুট থেকে খরচ/মাইলেজ হিসাব, একাধিক ইউজারকে শেয়ার (পরিবার), iOS Viewer/PWA |

## কোড কাঠামো
```
app/src/main/java/com/bikeguard/app/
  data/      Prefs, SupabaseApi, Room (offline queue), SyncManager, RealtimeClient, Perms
  tracker/   TrackingService, Guard (anti-theft/crash), AutoMode, Siren, SOS, Tile, Watchdog
  push/      FCM service + token registrar
  widget/    Home-screen widget
  ui/        AppRoot, auth/, tracker/, viewer/, trips/, alerts/, settings/, theme/
supabase/migrations/   SQL schema, RLS, trips, geofences, share RPCs
supabase/functions/push/   FCM sender (Edge Function)
docs/                  public share page (GitHub Pages)
.github/workflows/     CI: build + release
```
নতুন ফিচার যোগের নিয়ম: নতুন টেবিল/কলাম → `supabase/migrations/000N_*.sql`; নতুন ডিভাইস-ইভেন্ট → `tracker/`-এ; নতুন স্ক্রিন → `ui/`-এ।
