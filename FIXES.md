# إصلاحات GmsCore — تقرير التغييرات
# GmsCore fixes — changelog

تاريخ المراجعة: 2026-11-21 (قائمة issues/PRs المرفقة)
النطاق: إصلاحات أعطال/أخطاء برمجية محددة فقط، دون ميزات جديدة ودون إعادة هيكلة.

---

## 1. Cast — NPE في `SessionImpl.onApplicationConnectionFailure` (Issue #4005)

**الملف:** `play-services-cast-framework/core/src/main/java/com/google/android/gms/cast/framework/internal/SessionImpl.java`

**المشكلة:** كانت الدالة تُصفّر الحقل `castContext` ثم تستخدمه فورًا في السطر التالي
(`castContext.getSessionManagerImpl()`), ما يؤدي إلى NullPointerException مضمون عند فشل
الاتصال بالتطبيق. كما كانت `onApplicationConnectionSuccess` تعرّض نفس الحقل لخطر مماثل.

**الإصلاح:** الاحتفاظ بمرجع محلي `castContext` قبل تصفير الحقول، والتحقق من عدم كونه `null`
قبل استدعاء `onSessionStartFailed` و`selectDefaultRoute`. أُضيف نفس النمط إلى
`onApplicationConnectionSuccess` مع حماية قبل `onSessionStarted` و`selectRouteById`.

---

## 2. Cast — `ConcurrentModificationException` في `SessionManagerImpl` (Issue #2449)

**الملف:** `play-services-cast-framework/core/src/main/java/com/google/android/gms/cast/framework/internal/SessionManagerImpl.java`

**المشكلة:** مجموعات المستمعين كانت `HashSet`/`HashMap`، بينما تُضاف وتُحذف من خيوط Binder
وتُكرَّر (iterate) أثناء إرسال الإشعارات، ما يسبب `ConcurrentModificationException`.

**الإصلاح:** استبدالها بـ `CopyOnWriteArraySet` و`ConcurrentHashMap` (مع تعديل الاستيرادات
والتهيئة معًا لتفادي عدم تطابق النوع).

---

## 3. Cast — `CastDeviceControllerImpl` وحماية `app` / `app.namespaces`

**الملف:** `play-services-cast/core/src/main/java/org/microg/gms/cast/CastDeviceControllerImpl.java`

**المشكلة:** `createMetadataFromApplication` كانت تكرّر على `app.namespaces` دون التحقق من
`null`، و`launchApplication` كانت تصل إلى `app.sessionId` دون التحقق من أن المكتبة أعادت
تطبيقًا فعليًا.

**الإصلاح:** التحقق من `app.namespaces != null` قبل التكرار، وفي حال عدم وجود تطبيق بعد
`IOException` يتم الإبلاغ عن الفشل عبر `onApplicationConnectionFailure(INTERNAL_ERROR)`
بدل الانهيار.

---

## 4. Ads — `LegacyAdManager.getAdSize()` يعيد `null` (Issue #4020)

**الملفات:**
- `play-services-ads-api/core/src/main/kotlin/com/google/android/gms/ads/LegacyAdManager.kt`
- `play-services-ads-api/core/src/main/kotlin/com/google/android/gms/ads/AdManagerCreatorImpl.kt`

**المشكلة:** إصدارات AdMob القديمة تستدعي `getAdSize()` وتستخدم الناتج مباشرة، وكان يعيد
`null` عندما لا يمرّر العميل حجمًا، ما يؤدي إلى انهيار.

**الإصلاح:** تمرير `adSize` من `AdManagerCreatorImpl` إلى `LegacyAdManager`، وتخزينه في حقل
غير قابل للـ null مع قيمة احتياطية قياسية 320×50، وإرجاعه دائمًا من `getAdSize()`.
`setAdSize(null)` يُتجاهل بدل إفساد الحالة.

---

## 5. Location — `LocationResult` بدون موقع (Issue #3892)

**الملف:** `play-services-location/src/main/java/com/google/android/gms/location/LocationResult.java`

**المشكلة:** تمرير قائمة مواقع تحتوي `null` أو قائمة `null` كان يسبب NPE في
`getLastLocation()` و`equals()`/`hashCode()`.

**الإصلاح:** المُنشئ يوحّد القيمة إلى قائمة فارغة، و`create()` يستبعد العناصر `null`،
و`getLastLocation()` يتخطى القيم `null`، و`equals()`/`hashCode()` آمنتان ضد `null`.

---

## 6. Location — `LocationClientImpl`: موقع `null` وتسريب خريطة المستمعين

**الملف:** `play-services-location/src/main/java/org/microg/gms/location/LocationClientImpl.java`

**المشكلة:** `onLocationChanged` كانت تمرّر `null` إلى المستمع/الـ callback، و
`removeLocationUpdates` كانت تستدعي `map.get(...)` وتُجري استدعاءً عن بُعد حتى عند عدم
وجود تسجيل، مع بقاء المدخلات في الخريطة (تسريب).

**الإصلاح:** تجاهل `null` في جميع تطبيقات `ILocationListener.Stub` الثلاثة، واستخدام
`map.remove(...)` وإجراء الاستدعاء البعيد فقط عند وجود تسجيل فعلي.

---

## 7. Location — `ActivityRecognitionResult` عند قائمة أنشطة فارغة

**الملف:** `play-services-location/src/main/java/com/google/android/gms/location/ActivityRecognitionResult.java`

**المشكلة:** `getMostProbableActivity()` كانت تستدعي `probableActivities.get(0)` دون التحقق،
ما يسبب `IndexOutOfBoundsException` عند قائمة فارغة أو `NullPointerException` عند `null`.

**الإصلاح:** توحيد القائمة `null` إلى قائمة فارغة في المُنشئ، وإرجاع `null` من
`getMostProbableActivity()` عند عدم وجود عناصر، وحماية `getActivityConfidence()`.

---

## 8. Core — `LocationSharingReporter` مع عدم وجود حساب Google

**الملف:** `play-services-core/src/main/kotlin/com/google/android/gms/locationsharingreporter/service/LocationSharingReporterExtensions.kt`

**المشكلة:** `getAccountsByType(...)[0]` كانت تسبب `ArrayIndexOutOfBoundsException` عندما
لا يوجد أي حساب على الجهاز.

**الإصلاح:** استخدام `firstOrNull()` والتعامل مع غياب الحساب دون انهيار.

---

## 9. Core — `AccountAuthenticator` و`getCertificates().get(0)`

**الملف:** `play-services-core/src/main/java/org/microg/gms/auth/loginservice/AccountAuthenticator.java`

**المشكلة:** استدعاء `.get(0)` على قائمة شهادات قد تكون فارغة في
`isPackageOverrideAllowed` و`getAuthToken` → `IndexOutOfBoundsException`.

**الإصلاح:** جلب القائمة مرة واحدة، والتحقق من `isEmpty()`؛ في `isPackageOverrideAllowed`
تُعاد `false`، وفي `getAuthToken` يُعاد `Bundle` يحمل `ERROR_CODE_BAD_REQUEST` بدل الانهيار.

---

## ملاحظات

- لم تُطبَّق تغييرات على الميزات الكبيرة (RCS، WearOS، إعادة تصميم جلسات Cast، OCR) لأنها
  خارج نطاق «إصلاح ما يستحق الإصلاح» ولا يمكن التحقق منها دون بناء كامل.
- لم يُجرَ بناء (build) لأن بيئة العمل لا تحتوي JDK/Android SDK؛ التحقق تم بمراجعة الكود
  والتوقيعات وتوافق AIDL.
- الملفات المعدّلة: 10 ملفات، جميعها إصلاحات صغيرة وموضعية.
