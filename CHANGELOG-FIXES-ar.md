# إصلاحات GmsCore (ReVanced microG)

المصدر: `GmsCore-main.zip` — مطابق لآخر نسخة upstream (`776a8fb fix: Target own package for side by side installations`).
تمت مراجعة قائمة الـ 18 issue المفتوحة، وتم تنفيذ الإصلاحات الممكنة داخل الكود فقط، مع تجاهل ما هو خارج نطاق الكود (طلبات دعم، أجهزة محددة، تكرارات).

---

## 1) شاشة "About" لا تُظهر رقم الإصدار — Issue #13

**الملف:** `play-services-core/microg-ui-tools/src/main/java/org/microg/tools/ui/AbstractAboutFragment.java`

**السبب:** الدالة `getSelfVersion()` كانت تعتمد على `getLibVersion(context.getPackageName())` الذي يقرأ `BuildConfig.VERSION_NAME` من الحزمة المطابقة لـ **applicationId**. لكن `BuildConfig` يُولَّد في **namespace** الموديول، وهما مختلفان في نكهة `huawei` (`app.revanced.android.gms` مقابل `com.google.android.gms`)، وكذلك في أي تثبيت مُعاد التسمية أو التثبيت الجانبي (side-by-side). النتيجة: `null` أو سلسلة فارغة → الإصدار لا يظهر.

**الإصلاح:** قراءة `versionName` من `PackageManager` أولاً (وهي القيمة الحقيقية للحزمة المثبَّتة)، والرجوع إلى طريقة `BuildConfig` القديمة فقط عند فشل الاستعلام.

```java
try {
    String versionName = context.getPackageManager()
            .getPackageInfo(context.getPackageName(), 0).versionName;
    if (!TextUtils.isEmpty(versionName)) return versionName.trim();
} catch (PackageManager.NameNotFoundException ignored) { }
return getLibVersion(context.getPackageName());
```

---

## 2) أجهزة Chromecast مكرّرة + عدم وجود خيار لتعطيل الـ Casting — Issues #161 و #413

**السبب الجذري (سببان):**
1. عند تثبيت Google Play services إلى جانب GmsCore، يوجد **MediaRouteProvider** لكل منهما، فيظهر كل جهاز مرتين في قائمة التطبيقات (YouTube وغيرها).
2. داخل `CastMediaRouteProvider` كان `serviceCastIds.put(name, id)` يتم فقط داخل `if (!castDevices.containsKey(id))`. وعندما يُعلن الجهاز نفسه عبر أكثر من اسم DNS-SD (واجهات شبكة متعددة)، كانت الأسماء الإضافية لا تُسجَّل، فيبقى الجهاز "معلّقًا" بعد فقدان أحد الأسماء. كما كان `onChromeCastLost` يحذف الجهاز من أول اسم يختفي حتى لو كان لا يزال مُعلنًا باسم آخر.

**الإصلاح (من طرف إلى طرف):**

| الملف | التغيير |
|---|---|
| `play-services-base/core/.../settings/SettingsContract.kt` | إضافة `object Cast` مع `ID = "cast"` والمفتاح `cast_discovery_enabled` (الافتراضي `true`). |
| `play-services-base/core/.../settings/SettingsProvider.kt` | إضافة `queryCast()` و`updateCast()` وتوصيلهما في `query`/`update` — أصبح الإعداد متاحًا عبر ContentProvider لكل العمليات (processes). |
| `play-services-cast/core/.../cast/CastPreferences.kt` (ملف جديد) | `isCastDiscoveryEnabled()` / `setCastDiscoveryEnabled()` مع قيمة افتراضية آمنة `true` عند أي خطأ. |
| `play-services-cast/core/.../cast/CastMediaRouteProvider.java` | (أ) تسجيل كل أسماء DNS-SD للجهاز الواحد ونشره مرة واحدة فقط. (ب) `onChromeCastLost` لا يحذف الجهاز إلا إذا لم يبقَ أي اسم يشير إليه. (ج) عند تعطيل الخيار: إيقاف الاكتشاف ومسح الأجهزة والمسارات. (د) `publishRoutes()` ينشر descriptor فارغًا عند التعطيل. |
| `play-services-core/src/main/res/xml/preferences_start.xml` | إضافة `SwitchPreferenceCompat` بالمفتاح `pref_cast` داخل `prefcat_other_services`. |
| `play-services-core/src/main/kotlin/.../ui/SettingsFragment.kt` | ربط المفتاح: قراءة الحالة عند الفتح، والحفظ عند التغيير، وتحديث الحالة في `onResume`. |
| `play-services-core/src/main/res/values/strings.xml` | `pref_cast_title` و`pref_cast_summary` (يشرحان متى يجب التعطيل). |
| `play-services-core/src/main/res/drawable/ic_cast.xml` (ملف جديد) | أيقونة Cast (vector drawable). |

**الفائدة:** المستخدم يستطيع تعطيل اكتشاف Cast من الإعدادات → تختفي القائمة المكرّرة نهائيًا، بدون التأثير على بثّ Google Play services نفسه. والتحسين الثاني يمنع ظهور/اختفاء الأجهزة العشوائي على الشبكات متعددة الواجهات.

---

## 3) انهيار `AuthenticatorException: timeout` وتعليق تسجيل الدخول — Issues #384 و #395 و #411

**الملف:** `play-services-core/src/main/java/org/microg/gms/auth/loginservice/AccountAuthenticator.java`

**السبب:** `getAuthToken()` كان ينفّذ `authManager.requestAuthWithBackgroundResolution(true)` بشكل متزامن وغير محدود زمنيًا داخل الـ Binder thread الخاص بالـ authenticator. عند بطء الشبكة أو بقاء الـ WebView في انتظار المستخدم، يتجاوز النداء مهلة `AccountManager` فيقتل النظام العملية (`:persistent`) ويظهر تقرير الانهيار. كما كانت هناك ثلاث نقاط أخرى غير آمنة: `options.keySet()` بدون فحص `null`، و`i.putExtras(options)` بدون فحص، وإرجاع `null` من `editProperties` و`getAuthToken` في مسارات الفشل (وهو ما يفسّر أيضًا الـ spinner اللانهائي في #411 وعدم ظهور نافذة الدخول في #395).

**الإصلاح:**
- تنفيذ طلب المصادقة داخل `ExecutorService` (خيوط daemon باسم `gms-auth-request`) مع `future.get(8, SECONDS)` — أقل بكثير من مهلة الـ Binder.
- عند `TimeoutException` / `InterruptedException` / `ExecutionException`: إلغاء المهمة وإرجاع `Bundle` خطأ صحيح بدل `null`:
  ```java
  private Bundle errorResult(int code, String message) {
      Bundle result = new Bundle();
      result.putInt(KEY_ERROR_CODE, code);
      if (message != null) result.putString(KEY_ERROR_MESSAGE, message);
      return result;
  }
  ```
- `editProperties` يعيد `new Bundle()` بدل `null`.
- `addAccount`: فحص `options != null` قبل `putExtras`.
- `getAuthToken`: `if (options == null) options = Bundle.EMPTY;` بدل `options.keySet();`.
- `hasFeatures`: إرجاع `true` مباشرة عند `features == null || length == 0` بدل الاعتماد على `getUserData` (الذي قد يكون `null`).

**الفائدة:** لا مزيد من الانهيار في العملية `:persistent`؛ التطبيقات تحصل على خطأ مفهوم (`ERROR_CODE_NETWORK_ERROR`) بدل تعليق لا نهائي، وتظهر نافذة تسجيل الدخول بشكل موثوق.

---

## ما لم يتم إصلاحه (وسبب ذلك)

بقية الـ issues في القائمة ليست أخطاء برمجية قابلة للإصلاح في هذا المستودع:

- **#382** رسالة "new version available": نصّها يأتي من ReVanced Manager / Patch، وليس موجودًا في كود GmsCore (تم البحث في كل المصادر).
- **#377, #381, #388, #392, #400, #402, #403, #405, #408, #417, #420**: تقارير دعم متعلقة بجهاز/إصدار Patch/حساب معيّن، أو تكرارات للـ issues أعلاه. الحل الصحيح لها هو تحديث الـ Patch أو إعادة تثبيت، لا تعديل في GmsCore.
- **#161** (طلب ميزة تعطيل الـ double casting): تم تنفيذه ضمن البند 2.

---

## ملاحظة حول البناء

لا تتوفر في بيئة العمل هذه JDK أو Android SDK، لذلك **لم يتم تجميع المشروع**؛ جميع التغييرات تم التحقق منها بمراجعة الكود و`diff` مقابل نسخة upstream الأصلية. الملفات المعدّلة قليلة ومعزولة، ويمكن التحقق منها عبر:

```bash
diff -ru GmsCore-main-original GmsCore-main
```

ثم البناء المعتاد:

```bash
./gradlew assembleRelease
```
