# 🏍️ BikeGuard

নিজস্ব Android-ভিত্তিক বাইক GPS ট্র্যাকার। বাইকে রাখা একটি Android ফোন **Tracker**, আপনার হাতের ফোন **Viewer**। কোনো ESP32/GPS/GSM মডিউল লাগে না।

**Stack:** Kotlin · Jetpack Compose (Material 3) · MapLibre + OpenStreetMap (OpenFreeMap) · Supabase (Postgres + Auth + RLS) · Room · WorkManager · GitHub Actions

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
- বাইকের ফোন চার্জারে রাখুন; GPS-এ ব্যাটারি দ্রুত খরচ হয়
- Phase 1-এ Viewer প্রতি ৫ সেকেন্ডে সার্ভার থেকে পোল করে (লাইভ ম্যাপ খোলা থাকলে)। Realtime Phase 3-এ আসবে
- Release APK ডিফল্টে debug কী দিয়ে সাইন হয়। নিজের keystore দিতে `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` সেট করুন
- OpenFreeMap ফ্রি ও কী-ছাড়া, তবে অতিরিক্ত ট্রাফিকে নিজস্ব টাইল সার্ভার বা MapTiler ফ্রি টায়ারে যেতে হতে পারে। OSM attribution ম্যাপে দেখানো থাকে, সরাবেন না

## রোডম্যাপ
| Phase | বিষয় |
|---|---|
| 2 | Auto-tracking (Activity Recognition), Trip history ও replay, Adaptive battery (থামলে ধীর) |
| 3 | Geofence, Parking mode (নড়লেই অ্যালার্ট), FCM push, Tamper alert, Remote command, Realtime |
| 4 | Crash detection + SOS, Temporary share link, Biometric/PIN লক, Widget + Quick tile, Speed limit alert, Service reminder |

## কোড কাঠামো
```
app/src/main/java/com/bikeguard/app/
  data/      Prefs, SupabaseApi, Room (offline queue), SyncManager, Perms
  tracker/   TrackingService, WatchdogWorker, BootReceiver, TrackerState
  ui/        AppRoot, auth/, tracker/, viewer/ (MapLibre), settings/, theme/
supabase/migrations/   SQL schema + RLS
.github/workflows/     CI: build + release
```
নতুন ফিচার যোগের নিয়ম: নতুন টেবিল/কলাম → `supabase/migrations/000N_*.sql`; নতুন ডিভাইস-ইভেন্ট → `tracker/`-এ; নতুন স্ক্রিন → `ui/`-এ।
