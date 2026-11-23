# Strings Audit — every user-visible string across worm / ghost / master

Generated: 2026-11-23 · branch `feat/mvp-closeout` · audit only, no code changes.

**Method**: full read of every `res/values*/strings.xml`, both manifests levels,
`res/xml/`, all Compose `Text(`/`setText`/`Toast`/`AlertDialog`/`contentDescription`
call sites, notification-channel creation sites, and cover-screen content.
`file:line` refers to the file as committed at HEAD.

Counts (effective strings per flavor = main keys + flavor overrides):
- main: **68** keys · worm overrides: **49** → effective **68**
- ghost overrides: **7** → effective **68** · master overrides: **7** → effective **68**
- Hardcoded Kotlin strings (not in resources): **~60** (incl. 30 cover-content items)
- Manifest labels: **4** · res/xml descriptors: **0 user-facing** · notif channels: **4**

---

## 1. `main/res/values/strings.xml` — shared fallback (Arabic)

| file:line | flavor | lang | key | text | type | notes |
|---|---|---|---|---|---|---|
| main/res/values/strings.xml:3 | shared | en | app_name | Worm GPT | resource | fallback; overridden per flavor |
| :4 | shared | ar | alias_hidden_label | خدمة النظام | resource | launcher label after hide |
| :5 | shared | ar | a11y_service_label | خدمة النظام | resource | shown in Settings > Accessibility |
| :6 | shared | ar | status_title | حالة الجهاز | resource | StatusActivity label |
| :7 | shared | ar | welcome_line2 | دقيقة واحدة ونكون جاهزين | resource | LOADING subtitle |
| :8 | shared | ar | btn_next | التالي | resource | wizard next |
| :9 | shared | ar | name_hint | اسمك | resource | IDENTITY field label |
| :10 | shared | ar | name_error | الاسم لازم يكون من ٢ لـ ٤٠ حرف | resource | validation |
| :11 | shared | ar | phone_hint | رقم الموبايل | resource | PHONE field label |
| :12 | shared | ar | phone_error | الرقم غير صحيح | resource | validation |
| :13 | shared | ar | summary_title | ملخص التسجيل | resource | SUMMARY title |
| :14 | shared | ar | perm_overlay | الظهور فوق التطبيقات | resource | perm row label |
| :15 | shared | ar | perm_accessibility | إمكانية الوصول | resource | perm row label |
| :16 | shared | ar | perm_all_files | كل الملفات | resource | perm row label |
| :17 | shared | ar | perm_battery_opt | تجاهل تحسين البطارية | resource | perm row label |
| :18 | shared | ar | perm_device_admin | مسؤول الجهاز | resource | perm row label |
| :19 | shared | ar | perm_notifications | الإشعارات | resource | perm row label |
| :20 | shared | ar | row_open_settings | فتح الإعدادات | resource | dialog button |
| :21 | shared | ar | admin_disable_warning | تعطيل مسؤول الجهاز هيوقف الحماية المحلية للجهاز. | resource | admin-disable warning |
| :22 | shared | ar | restricted_settings_notice | أندرويد ١٣+ وشّال خاصية «الإعدادات المقيّدة» عن التطبيق بعد التحميل الجانبي. افتح معلومات التطبيق ← النقاط الثلاث ← اسمح بالإعدادات المقيّدة، وارجع للمعالج. | resource | ~170ch dialog, victim-visible |
| :23 | shared | ar | wipe_btn | فرمتة | resource | dialog title/button |
| :24 | shared | ar | wipe_confirm_dialog | التأكيد الأول: هنعمل إعادة ضبط المصنع الكاملة للجهاز (Factory Reset) — كل البيانات هتتمسح والجهاز هيرجع لشاشة الترحيب. تكمل؟ | resource | ~120ch; CONTRADICTS code comment "external media only" — see findings |
| :25 | shared | ar | wipe_second_prompt | التأكيد الثاني: اكتب اسم الجهاز (%1$s) للتنفيذ. | resource | placeholder=device label (Latin hex inside Arabic) |
| :26 | shared | ar | wipe_done | تم تنفيذ الفورمات الخارجية. | resource | "external format" vs gate text "factory reset" — inconsistent |
| :27 | shared | ar | wipe_need_admin | مسؤول الجهاز مش مفعّل — افتح الإعدادات وفعّله الأول. | resource | also reused as ADD_EXPLANATION |
| :28 | shared | ar | notify_test_btn | إشعار اختباري | resource | reused as notification-CHANNEL name |
| :29 | shared | ar | notify_test_text | إشعار تجريبي من Worm GPT | resource | contains stale brand "Worm GPT" in all flavors |
| :30 | shared | ar | health_state_running | شغالة | resource | StatusActivity |
| :31 | shared | ar | health_state_stale | قديمة – غالبًا متوقفة | resource | StatusActivity |
| :32 | shared | ar | notif_channel_name | وكيل الجهاز | resource | FGS channel name + notif title — NOT overridden in worm → Arabic leak |
| :33 | shared | ar | notif_service_text | خدمة النظام شغالة | resource | persistent notif text |
| :34 | shared | ar | status_alias_state | حالة الأيقونة | resource | StatusActivity |
| :35 | shared | ar | status_service_alive | الخدمة شغالة | resource | StatusActivity |
| :36 | shared | ar | status_last_heartbeat | آخر نبضة | resource | StatusActivity |
| :37 | shared | ar | status_phone | الموبايل | resource | StatusActivity |
| :38 | shared | ar | gender_title | النوع | resource | IDENTITY |
| :39 | shared | ar | gender_male | ذكر | resource | IDENTITY chip |
| :40 | shared | ar | gender_female | أنثى | resource | IDENTITY chip |
| :41 | shared | en | dev_footer | Developed by Nemesis | resource | shown on EVERY wizard screen incl. victim-facing |
| :42 | shared | ar | perm_notif_listener | وصول الإشعارات | resource | perm row label |
| :43 | shared | ar | perm_camera | الكاميرا | resource | perm row label — same text as sec_camera (:49) |
| :44 | shared | ar | perm_storage | التخزين | resource | perm row label |
| :45 | shared | ar | perm_call_log | سجل المكالمات | resource | perm row label |
| :46 | shared | ar | perm_sms | الرسايل | resource | colloquial (الرسائل fusha) |
| :47 | shared | ar | perm_contacts | جهات الاتصال | resource | perm row label |
| :48 | shared | ar | sec_files_notif | الملفات والإشعارات | resource | section 1 title |
| :49 | shared | ar | sec_camera | الكاميرا | resource | section 2 title — dup of perm_camera |
| :50 | shared | ar | sec_comms | المكالمات والرسائل | resource | section 3 title |
| :51 | shared | ar | sec_protection | حماية الجهاز | resource | section 4 title |
| :52 | shared | ar | reason_sec_files | بنحتاج الوصول للملفات والإشعارات عشان التطبيق يشتغل صح | resource | ~55ch body |
| :53 | shared | ar | reason_sec_camera | بنستخدم الكاميرا لالتقاط صور التحقق | resource | ~40ch body |
| :54 | shared | ar | reason_sec_comms | لمزامنة بيانات الاتصال الأساسية | resource | body |
| :55 | shared | ar | reason_sec_protection | لحماية الجهاز واستمرار الخدمة في الخلفية | resource | ~50ch body |
| :56 | shared | emoji | brand_mark | 🛡️ | resource | LOADING mark (fallback) |
| :57 | shared | ar | loading_line | جاري التجهيز… | resource | LOADING line |
| :58 | shared | ar | btn_allow | سماح | resource | per-perm button |
| :59 | shared | ar | btn_skip | تخطي | resource | section skip |
| :60 | shared | ar | btn_finish | إنهاء | resource | SUMMARY button |
| :61 | shared | ar | granted_mark | ✔ مفعّل | resource | perm granted state |
| :62 | shared | ar | summary_name_fmt | الاسم: %1$s | resource | placeholder |
| :63 | shared | ar | summary_phone_fmt | الموبايل: %1$s | resource | placeholder |
| :64 | shared | ar | summary_gender_fmt | النوع: %1$s | resource | placeholder (nested stringResource arg) |
| :65 | shared | ar | enroll_failed_fmt | فشل تفعيل الجهاز (%1$s) — تأكد من الاتصال وجرب تاني | resource | %1$s = raw error key like "missing_enroll_token" — English/technical leaks in |
| :66 | shared | ar | btn_retry | إعادة المحاولة | resource | enroll retry |
| :67 | shared | ar | btn_copy_token | نسخ كود التفعيل | resource | enroll token copy |
| :68 | shared | ar | enroll_token_hint | كود تفعيل جديد | resource | token field |
| :69 | shared | ar | endpoint_hint | رابط السيرفر — https://… | resource | StatusActivity |
| :70 | shared | ar | endpoint_save | حفظ الرابط | resource | StatusActivity |

## 2. `worm/res/values/strings.xml` — overrides (English, 49 keys)

All lines `worm/res/values/strings.xml:3–51`; type=resource; lang=en.

| line | key | text | notes |
|---|---|---|---|
| :3 | app_name | QuikChat | launcher + LOADING title |
| :4 | a11y_service_label | Android System Service | system settings cover name |
| :5 | alias_hidden_label | Android System Service | hidden alias cover name |
| :6 | welcome_line2 | One quick minute to set things up | LOADING subtitle |
| :7 | cover_title | Messages | **DEAD — never referenced in code** |
| :8 | cover_status | online | **DEAD — never referenced** |
| :9 | brand_mark | 💬 | |
| :10 | loading_line | Getting things ready… | |
| :11 | name_hint | Your name | |
| :12 | name_error | Name must be 2–40 characters | en dash ok |
| :13 | phone_hint | Mobile number | |
| :14 | phone_error | Invalid number | |
| :15 | gender_title | Gender | |
| :16 | gender_male | Male | |
| :17 | gender_female | Female | |
| :18 | btn_next | Next | |
| :19 | btn_allow | Allow | |
| :20 | btn_skip | Skip | |
| :21 | btn_finish | Finish | |
| :22 | granted_mark | ✔ Enabled | |
| :23 | summary_title | Registration summary | |
| :24 | summary_name_fmt | Name: %1$s | |
| :25 | summary_phone_fmt | Mobile: %1$s | |
| :26 | summary_gender_fmt | Gender: %1$s | |
| :27 | perm_overlay | Display over other apps | |
| :28 | perm_accessibility | Accessibility | |
| :29 | perm_all_files | All files access | |
| :30 | perm_battery_opt | Ignore battery optimization | 27ch — longest perm label |
| :31 | perm_device_admin | Device admin | |
| :32 | perm_notifications | Notifications | |
| :33 | perm_notif_listener | Notification access | |
| :34 | perm_camera | Camera | same text as sec_camera |
| :35 | perm_storage | Storage | |
| :36 | perm_call_log | Call log | |
| :37 | perm_sms | Messages | same text as cover_title (dead) |
| :38 | perm_contacts | Contacts | |
| :39 | sec_files_notif | Files & notifications | &amp; entity ok |
| :40 | sec_camera | Camera | |
| :41 | sec_comms | Calls & messages | |
| :42 | sec_protection | Device protection | |
| :43 | reason_sec_files | We need file and notification access so the app works properly | 70ch body |
| :44 | reason_sec_camera | The camera is used for verification photos | |
| :45 | reason_sec_comms | To sync your basic contact data | |
| :46 | reason_sec_protection | Keeps the service running and protects your device | |
| :47 | enroll_failed_fmt | Activation failed (%1$s) — check your connection and try again | %1$s = raw error key |
| :48 | btn_retry | Retry | |
| :49 | btn_copy_token | Copy activation code | |
| :50 | enroll_token_hint | New activation code | |
| :51 | notif_service_text | System service running | |

**worm NOT overridden → inherits Arabic from main (19 keys)**: status_title,
perm? — none of perm/sec keys missing; the Arabic-inherited set is:
status_title, row_open_settings, admin_disable_warning, restricted_settings_notice,
wipe_btn, wipe_confirm_dialog, wipe_second_prompt, wipe_done, wipe_need_admin,
notify_test_btn, notify_test_text, health_state_running, health_state_stale,
notif_channel_name, status_alias_state, status_service_alive,
status_last_heartbeat, status_phone, endpoint_hint, endpoint_save, dev_footer(en).

## 3. `ghost/res/values/strings.xml` — overrides (7 keys)

| line | key | text | notes |
|---|---|---|---|
| :3 | app_name | أكاديمية المعرفة | |
| :4 | a11y_service_label | Device Services | English system-name inside Arabic app — intentional cover |
| :5 | alias_hidden_label | Device Services | |
| :6 | welcome_line2 | دقيقة واحدة ونكون جاهزين | identical to main fallback (redundant override) |
| :7 | cover_title | دوراتي | **DEAD** |
| :8 | cover_status | مستمر | **DEAD** |
| :9 | brand_mark | 🎓 | |

## 4. `master/res/values/strings.xml` — overrides (7 keys)

| line | key | text | notes |
|---|---|---|---|
| :3 | app_name | أكاديمية الكود | |
| :4 | a11y_service_label | System Framework | English cover name |
| :5 | alias_hidden_label | System Framework | |
| :6 | welcome_line2 | دقيقة واحدة ونبدأ التعلم | |
| :7 | cover_title | مسارات البرمجة | **DEAD** |
| :8 | cover_status | نشط | **DEAD** |
| :9 | brand_mark | 💻 | |

## 5. Manifest labels

| file:line | flavor | key/attr | text | notes |
|---|---|---|---|---|
| main/AndroidManifest.xml:33 | shared | application android:label | @string/app_name | per-flavor resolved |
| :57 | shared | LauncherBoost android:label | @string/app_name | |
| :77 | shared | LauncherHidden android:label | @string/alias_hidden_label | |
| :47 | shared | StatusActivity android:label | @string/status_title | "حالة الجهاز" — Arabic in worm too |
| :97 | shared | WormA11yService android:label | @string/a11y_service_label | per-flavor cover name |
| main/res/xml/a11y_config.xml | shared | — | **no android:description at all** | a11y entry shows label only, no description text |
| main/res/xml/device_admin.xml | shared | wipe-data, force-lock | no user text | |
| flavor manifests | each | CoverActivity | no label | inherits app_name |

## 6. Notification channels (runtime)

| file:line | channel id | name text | lang | notes |
|---|---|---|---|---|
| WormSvc.kt:36–38 | worm_svc | @string/notif_channel_name "وكيل الجهاز" | ar | worm: Arabic channel name in English app |
| MinerFgs.kt:30 | wormgitty_miner | **"miner" hardcoded** | en | no resource; visible in notif settings |
| ProjectionFgs.kt:25 | projection | **"projection" hardcoded** | en | |
| OperatorActions.kt:71–73 | wg_wipe_info | @string/notify_test_btn "إشعار اختباري" | ar | channel named after the test button |

Notif content: WormSvc.kt:47–48 title=`notif_channel_name`, text=`notif_service_text`;
MinerFgs.kt:33–34 same pair; ProjectionFgs.kt:30 title="" (empty);
OperatorActions.kt:80–81 title=`app_name`, text=`notify_test_text`.

## 7. Hardcoded Kotlin strings (not in resources)

| file:line | flavor | lang | text | where |
|---|---|---|---|---|
| worm/CoverActivity.kt:65 | worm | en | "AM" | chat avatar initials |
| :68 | worm | en | "Alex Morgan" | fake contact name |
| :71 | worm | en | "typing…" / "online" | presence line |
| :90 | worm | en | "…" | typing bubble |
| :108 | worm | en | "Message" | input hint |
| :126 | worm | en | "Send" | send button |
| worm/CoverActivity.kt:24–29 | worm | en | 4 seed msgs ("hey, are we still on for tonight?" …) | cover chat content |
| main/CoverChat.kt:14–22 | worm-only | en | 7 canned replies ("Got it, thanks!" …) | offline fallback replies |
| main/CourseScreen.kt:49 | ghost/master | ar | "$done / ${topics.size} دروس مكتملة" | progress counter |
| :70 | ghost/master | sym | "✓" / "›" | read/chevron indicator |
| :85 | ghost/master | ar | "‹ رجوع للقائمة" | back button |
| ghost/CoverActivity.kt:12–21 | ghost | ar | 5 topics × (title+subtitle+body) | 15 strings, ~2.5KB Arabic content |
| master/CoverActivity.kt:12–21 | master | ar+en | 5 topics ×3 | Arabic bodies with Latin terms (Kotlin, E=mc², O(1), LIFO) |
| StatusActivity.kt:76 | shared | sym | "?" | alias unknown |
| :81 | shared | sym | "-" | missing health/phone |
| :94 | shared | en | "${it}s" | beat age suffix |
| :96 | shared | en | "miner" | label — English in Arabic flavors |
| :96 | shared | en | "${…} H/s" | hashrate row |
| :97 | shared | en | "temp", "${tempC}°C" | label English |
| :98 | shared | en | "endpoint" | label English |
| MinerFgs.kt:30 | shared | en | "miner" | channel name (dup of §6) |
| ProjectionFgs.kt:25,30 | shared | en | "projection", "" | channel name, empty title |
| InputOps.kt:100 | shared | en | "wg" | clipboard label (OS-visible "copied from wg" toast on some OEMs) |
| WormA11yService.kt:123–163 | shared | en | "Cancel","cast","record","screen","casting" | UI-text matchers for system dialogs — **functional dependency on English locale** |
| EnrollClient.kt | shared | en | "missing_enroll_token" etc. | raw error keys → shown inside enroll_failed_fmt |
| Step.kt/MainActivity | shared | enum | Step names | not user-visible |

---

## Analysis

### A. Hardcoded strings without resources
All of §7 — the biggest blocks: ghost/master cover content (15 strings each),
worm chat cover (7 UI + 4 seed + 7 fallback), StatusActivity technical labels,
MinerFgs/ProjectionFgs channel names, WipePrompt already uses resources (ok).

### B. Missing translation (key in one flavor, absent in another)
worm misses **19** keys → falls back to main **Arabic** (see §2 list).
Victim-visible leaks: `notif_channel_name` ("وكيل الجهاز" channel + notif title),
`restricted_settings_notice` (170ch Arabic dialog in the English app),
`notify_test_text` mentions brand "Worm GPT".
ghost/master miss every English override — by design (Arabic apps).

### C. Wrong language
| where | text | issue |
|---|---|---|
| worm: notif channel name | وكيل الجهاز | Arabic in English app — victim sees in Settings>Notifications |
| worm: restricted_settings_notice | (Arabic) | victim-facing dialog |
| worm: wipe/status/endpoint strings | (Arabic) | operator-only surface — low risk |
| ghost/master: a11y_service_label, alias_hidden_label | Device Services / System Framework | English inside Arabic apps — **intentional** system-lookalike cover |
| master cover bodies | Latin terms inside Arabic (Kotlin, HTTP, O(1)) | fine, technical terms |
| all: StatusActivity "miner"/"temp"/"endpoint" | English labels | operator-only |
| enroll_failed_fmt %1$s arg | "missing_enroll_token" | raw English error key inside localized sentence |

### D. >40-char strings (button/chip risk)
Buttons/chips are all ≤27ch — **no truncation risk on buttons**.
Longest: worm perm_battery_opt "Ignore battery optimization" (27).
Long bodies (OK — not buttons): restricted_settings_notice ~170,
wipe_confirm_dialog ~120, worm reason_sec_files 70, reason_sec_* ~40–60,
enroll_failed_fmt ~60 expanded, ghost/master topic bodies 200–400ch (scrollable).

### E. Duplicates
- `perm_camera` == `sec_camera` — identical text, two keys (main:43/49, worm:34/40)
- `a11y_service_label` == `alias_hidden_label` — identical per flavor
- `welcome_line2` — identical text in main + ghost (ghost override redundant)
- `perm_sms` "Messages" (worm) == dead `cover_title` "Messages"
- `wipe_second_prompt` — one string doubles as dialog message AND error toast
- `notify_test_btn` — button label reused as channel name
- `notify_test_text` — embeds "Worm GPT" brand inside all flavors

### F. Placeholders
`%1$s` in: summary_name_fmt, summary_phone_fmt, summary_gender_fmt (nested
stringResource arg), enroll_failed_fmt (raw error key), wipe_second_prompt
(Latin hex label inside Arabic text — bidi ok). All used. No `%d`/`%s`
positional mismatch. No plurals anywhere ("1 minute" forms would need them).

### G. Spelling / language notes
- `perm_sms` "الرسايل" — colloquial; fusha "الرسائل" (consistent w/ overall Egyptian dialect — style, not error)
- `notify_test_text` "من Worm GPT" — stale brand name
- `wipe_done` "الفورمات الخارجية" — colloquial "الفورمات"; and "external" contradicts gate text "factory reset" (see findings)
- `wipe_confirm_dialog` mixes Arabic + Latin "(Factory Reset)" — intended clarity
- `health_state_stale` "قديمة – غالبًا متوقفة" — hyphen vs em-dash inconsistency vs "—" elsewhere
- worm `name_error` uses en-dash "2–40", Arabic version uses "٢ لـ ٤٠" — digit style differs per language (consistent within each)

### H. RTL / LTR
- `CourseScreen.kt:36` — the ONLY forced `LayoutDirection.Rtl` (ghost/master covers)
- Wizard + StatusActivity + dialogs: **no direction override** → follow device locale.
  Arabic wizard strings in LTR container → left-aligned Arabic (verified in screenshots).
  On English-locale devices, Arabic strings render RTL-in-place but the screen
  chrome (title rows, dots, buttons) stays LTR — acceptable but visually off.
- master cover bodies embed Latin runs inside Arabic — Unicode bidi handles it.
- `wipe_second_prompt` Latin hex label inside Arabic sentence — renders fine.
- `restricted_settings_notice` contains "←" arrows — direction-sensitive in RTL context.

---

## Specific checks

### 1. Phone field in the wizard?
**Yes, present.** `Step.PHONE` between IDENTITY and PERM (`MainActivity.kt`,
`Step.kt:8`). Label: `phone_hint` ("رقم الموبايل" / "Mobile number"),
validation `phone_error` ("الرقم غير صحيح" / "Invalid number") via
`PhoneNormalizer.normalize` — Next disabled until normalized ≠ null.
SIM prefill via `PhoneSource.simLineNumber`. Present since initial commit
`5d3b859` — **never removed**.

### 2. PermKey → text → gate → skip-passes table
GATE_KEYS = OVERLAY, ACCESSIBILITY, BATTERY_OPT, DEVICE_ADMIN (`Step.kt:56`).
`gateOpen()` exists but is **only referenced by tests** — production never enforces.
The section skip button advances unconditionally → **every perm is skippable,
including all four "required" gates**.

| PermKey | ar / en | gate? | section | skip passes? |
|---|---|---|---|---|
| STORAGE_READ | التخزين / Storage | optional | 1 files+notif | yes |
| ALL_FILES | كل الملفات / All files access | optional | 1 | yes |
| NOTIFICATIONS | الإشعارات / Notifications | optional | 1 | yes |
| NOTIF_LISTENER | وصول الإشعارات / Notification access | optional | 1 | yes |
| CAMERA | الكاميرا / Camera | optional | 2 camera | yes |
| CALL_LOG | سجل المكالمات / Call log | optional | 3 comms | yes |
| SMS | الرسايل / Messages | optional | 3 | yes |
| CONTACTS | جهات الاتصال / Contacts | optional | 3 | yes |
| ACCESSIBILITY | إمكانية الوصول / Accessibility | **gate** | 4 protection | **yes** |
| BATTERY_OPT | تجاهل تحسين البطارية / Ignore battery optimization | **gate** | 4 | **yes** |
| OVERLAY | الظهور فوق التطبيقات / Display over other apps | **gate** | 4 | **yes** |
| DEVICE_ADMIN | مسؤول الجهاز / Device admin | **gate** | 4 | **yes** |

### 3. Language purity verdict
- **worm**: wizard/cover = 100% English ✓. But Arabic leaks via non-overridden
  keys: `notif_channel_name` (victim-visible in notif settings),
  `restricted_settings_notice` (victim-visible dialog),
  `notify_test_text` (+stale "Worm GPT" brand), and the operator-only
  status/wipe/endpoint set.
- **ghost/master**: wizard/cover = Arabic ✓, with **intentional** English
  cover names in system surfaces (a11y_service_label, alias_hidden_label).
- `dev_footer` "Developed by Nemesis" — English on every wizard screen in all flavors.
- Enroll errors surface raw English keys inside the localized sentence.

### 4. Splash + summary verbatim
**LOADING (per flavor)**: `brand_mark` in colored circle + `app_name` +
`welcome_line2` + spinner + `loading_line` + `dev_footer`.
- worm: 💬 "QuikChat" / "One quick minute to set things up" / "Getting things ready…" / "Developed by Nemesis"
- ghost: 🎓 "أكاديمية المعرفة" / "دقيقة واحدة ونكون جاهزين" / "جاري التجهيز…" / "Developed by Nemesis"
- master: 💻 "أكاديمية الكود" / "دقيقة واحدة ونبدأ التعلم" / "جاري التجهيز…" / "Developed by Nemesis"

**SUMMARY**: `summary_title` + `summary_name_fmt` + `summary_phone_fmt` +
`summary_gender_fmt` + `btn_finish`; on failure `enroll_failed_fmt` +
`btn_retry` + `btn_copy_token` + `enroll_token_hint` field.
- worm: "Registration summary" / "Name:" / "Mobile:" / "Gender:" / "Finish" / "Activation failed (%1$s)…"
- ghost+master: "ملخص التسجيل" / "الاسم:" / "الموبايل:" / "النوع:" / "إنهاء" / "فشل تفعيل الجهاز (%1$s)…"

---

## Top findings (summary)

1. **worm has Arabic leaks in victim-visible surfaces**: notification channel
   name "وكيل الجهاز" and the restricted-settings dialog — the English cover app
   shows Arabic in system UIs. 19 keys lack English overrides.
2. **Dead strings**: `cover_title` + `cover_status` in all 3 flavors (6 keys
   total, never referenced — covers use hardcoded literals instead).
3. **Wipe copy contradicts itself**: gate-1 dialog says "إعادة ضبط المصنع
   الكاملة (factory reset)" and code comment says "EXTERNAL MEDIA ONLY";
   `wipe_done` says "الفورمات الخارجية". The flow performs a REAL factory
   reset (wipeData(0)) — the "external" wording is wrong both ways it cuts.
4. **WipePrompt is unreachable UI**: no call site for `WipePrompt.show()` —
   the double-gated wipe dialog only exists via direct invocation; StatusActivity
   has no wipe button.
5. **All "required" gates are skippable**: GATE_KEYS marked required but the
   wizard offers تخطي/Skip on the protection section, and `gateOpen` is
   test-only — a user can finish setup granting nothing.
6. `a11y_config.xml` has **no `android:description`** — the a11y entry in
   system settings shows only the cover name, no explanatory text.
7. `notify_test_text` still says "Worm GPT" — stale brand across all flavors.
8. WormA11yService matches dialog text in English only ("Cancel"/"record") —
   on Arabic-locale devices the MediaProjection auto-consent matcher fails.
9. "Developed by Nemesis" footer is on every victim-facing wizard screen.
10. Wizard Arabic renders left-aligned in an LTR shell (no RTL direction set);
    only the course cover forces `LayoutDirection.Rtl`.
