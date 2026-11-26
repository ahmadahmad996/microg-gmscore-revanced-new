# دمج تعديلات ReVanced في النسخة الأصلية (master)

القاعدة: **GmsCore-master** (microG الأصلي، الإصدار `v0.3.18.252432`، آخر commit `f686900`) مع كل إصلاحاته الموجودة في `FIXES.md`.
فوقها تم تطبيق:

1. **تعديلات ReVanced السبعة** (من `dbd68770` إلى `776a8fb7` في مستودع ReVanced/GmsCore):
   - إعادة تسمية الحزمة إلى `app.revanced.android.gms` (نكهات `user` و`huawei`).
   - إعادة تسمية كل الأذونات والمزوّدات (authorities) ونوع الحساب `app.revanced` للتثبيت بجانب Google Play Services أو microG.
   - `PackageSpoofUtils` لدعم التطبيقات المعدّلة عبر باتش "GmsCore support".
   - استهداف الحزمة نفسها في الاتصالات الداخلية (`MultiConnectionKeeper`, `GooglePlayServicesUtil`, ...).
   - تفعيل Checkin / GCM / DroidGuard / SafetyNet افتراضيًا.
   - إصلاح تحميل خرائط Mapbox من داخل الحزمة نفسها.
   - سير عمل الإصدار `release.yml` وشرح الـ fork في `README.md`.
2. **إصلاحات نسخة main** (`CHANGELOG-FIXES-ar.md`): رقم الإصدار في شاشة About، خيار تعطيل اكتشاف Cast، مهلة تسجيل الدخول.

## ما تم تعديله يدويًا لأن كود master تغيّر

| الملف | السبب |
|---|---|
| `play-services-core/build.gradle` | نكهة `huaweilh` غير موجودة في master؛ طُبّقت إعادة التسمية على `huawei` و`user` فقط. |
| `play-services-ads-api/src/main/AndroidManifest.xml` | إذن `AD_ID` انتقل في master من `ads-lite` إلى `ads-api`، فأُعيدت تسميته هناك. |
| `MultiArchLoader.kt` | اعتُمدت نسخة ReVanced (تحميل المكتبات من الحزمة نفسها). |
| `auth/gcm/GcmReceiverService.kt` | كود الإشعارات انتقل في master إلى هذا الملف؛ أصبح يستهدف الحزمة نفسها. |
| `gcm/GcmInGmsService.kt` | بث `MESSAGE_RECEIVE` الجديد في master أصبح يستهدف الحزمة نفسها. |
| `credentials/identity/AuthorizationService.kt` | نافذة تسجيل الدخول تستهدف الحزمة نفسها. |
| `AccountAuthenticator.java` | دمج إصلاح master (قائمة الشهادات الفارغة) + إصلاح main (المهلة 8 ثوانٍ وفحوص null) مع الحفاظ على مسار إعادة المصادقة الجديد في master. كما صُحّح موضع تعديل ReVanced (`spoofPackageName`) ليكون في `AskPermissionActivity`. |
| `build.gradle` (الجذر) | عند البناء بدون git يُستخدم إصدار master `0.3.18.252432` بدل `v0.0.0`. يمكن تغييره بالمتغير `GRADLE_MICROG_VERSION_BASE`. |

## البناء

```bash
export GRADLE_MICROG_VERSION_WITHOUT_GIT=1   # عند البناء من ملف zip بدون .git
./gradlew :play-services-core:assembleMapboxUserRelease     # مثل ReVanced
./gradlew :play-services-core:assembleMapboxHuaweiRelease   # لأجهزة هواوي
```

تم التحقق: البناء ناجح (JDK 17، Android SDK 35)، اسم الحزمة `app.revanced.android.gms`،
الإصدار `0.3.18.252432` (`252432036`)، كل الأذونات المعرّفة (27) وكل المزوّدات (12) تبدأ بـ `app.revanced`
فلا يوجد تعارض مع Google Play Services أو microG عند التثبيت جنبًا إلى جنب.

## اسم التطبيق

نكهة `user` تستخدم الاسم **microG Services** (`@string/gms_app_name`) بدل "microG Limited Services".
