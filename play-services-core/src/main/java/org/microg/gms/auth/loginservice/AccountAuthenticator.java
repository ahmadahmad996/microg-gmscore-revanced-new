/*
 * SPDX-FileCopyrightText: 2015 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.loginservice;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.NetworkErrorException;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;

import com.google.android.gms.common.internal.CertData;
import org.microg.gms.accountaction.ErrorResolverKt;
import org.microg.gms.accountaction.Reauthenticate;
import org.microg.gms.accountaction.Resolution;
import org.microg.gms.auth.*;
import org.microg.gms.auth.login.LoginActivity;
import org.microg.gms.common.NotOkayException;
import org.microg.gms.common.PackageUtils;
import org.microg.gms.auth.AuthResponse;
import org.microg.gms.ui.UnpackingRedirectActivity;
import org.microg.gms.utils.PackageManagerUtilsKt;

import org.microg.gms.common.PackageSpoofUtils;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static android.accounts.AccountManager.*;
import static android.os.Build.VERSION.SDK_INT;
import static org.microg.gms.auth.AskPermissionActivity.EXTRA_FROM_ACCOUNT_MANAGER;

import androidx.core.app.PendingIntentCompat;

public class AccountAuthenticator extends AbstractAccountAuthenticator {
    private static final String TAG = "GmsAuthenticator";
    public static final String KEY_OVERRIDE_PACKAGE = "overridePackage";
    public static final String KEY_OVERRIDE_CERTIFICATE = "overrideCertificate";
    // AccountManager kills the caller when an authenticator does not answer within its
    // binder timeout. Stay comfortably below that limit and report an error instead.
    private static final long AUTH_TIMEOUT_SECONDS = 8;
    private static final ExecutorService AUTH_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "gms-auth-request");
        thread.setDaemon(true);
        return thread;
    });
    private final Context context;

    public AccountAuthenticator(Context context) {
        super(context);
        this.context = context;
    }

    @Override
    public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
        Log.d(TAG, "editProperties: " + accountType);
        throw new UnsupportedOperationException();
    }

    @Override
    public Bundle addAccount(AccountAuthenticatorResponse response, String accountType, String authTokenType, String[] requiredFeatures, Bundle options) throws NetworkErrorException {
        final Bundle result = new Bundle();
        if (accountType.equals(AuthConstants.DEFAULT_ACCOUNT_TYPE)) {
            final Intent i = new Intent(context, LoginActivity.class);
            if (options != null) i.putExtras(options);
            i.putExtra(LoginActivity.EXTRA_TMPL, LoginActivity.TMPL_NEW_ACCOUNT);
            i.putExtra(KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response);
            result.putParcelable(KEY_INTENT, i);
            return result;
        } else if (accountType.equals(AuthConstants.WORK_ACCOUNT_TYPE)) {
            result.putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION);
            result.putString(AccountManager.KEY_ERROR_MESSAGE, context.getString(org.microg.gms.auth.workaccount.R.string.auth_work_authenticator_add_manual_error));
        } else {
            result.putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION);
        }
        return result;
    }

    @Override
    public Bundle confirmCredentials(AccountAuthenticatorResponse response, Account account, Bundle options) throws NetworkErrorException {
        Log.d(TAG, "confirmCredentials: " + account + ", " + options);
        final Bundle result = new Bundle();
        result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true);
        return result;
    }

    @Override
    public Bundle getAccountRemovalAllowed(AccountAuthenticatorResponse response, Account account) throws NetworkErrorException {
        if (account.type.equals(AuthConstants.WORK_ACCOUNT_TYPE)) {
            final Bundle result = new Bundle();
            result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, SDK_INT < 22);
            return result;
        }
        return super.getAccountRemovalAllowed(response, account);
    }

    public boolean isPackageOverrideAllowed(Account account, String requestingPackage, String overridePackage, CertData overrideCertificate) {
        // Always allow for self package
        if (requestingPackage.equals(context.getPackageName())) return true;
        List<CertData> requestingCertificates = PackageManagerUtilsKt.getCertificates(context.getPackageManager(), requestingPackage);
        if (requestingCertificates.isEmpty()) return false;
        String requestingDigestString = PackageManagerUtilsKt.toHexString(PackageManagerUtilsKt.digest(requestingCertificates.get(0), "SHA-256"), "");
        String overrideCertificateDigestString = PackageManagerUtilsKt.toHexString(PackageManagerUtilsKt.digest(overrideCertificate, "SHA-256"), "");
        String overrideUserDataKey = "override." + requestingPackage + ":" + requestingDigestString + ":" + overridePackage + ":" + overrideCertificateDigestString;
        String hasOverride = AccountManager.get(context).getUserData(account, overrideUserDataKey);
        return "1".equals(hasOverride);
    }

    @Override
    public Bundle getAuthToken(AccountAuthenticatorResponse response, Account account, String authTokenType, Bundle options) throws NetworkErrorException {
        if (options == null) options = Bundle.EMPTY;
        Log.d(TAG, "getAuthToken: " + account + ", " + authTokenType + ", " + options);
        String app = options.getString(KEY_ANDROID_PACKAGE_NAME);
        app = PackageUtils.getAndCheckPackage(context, app, options.getInt(KEY_CALLER_UID), options.getInt(KEY_CALLER_PID));
        AuthManager authManager;
        if (app == null) {
            Bundle result = new Bundle();
            result.putInt(KEY_ERROR_CODE, ERROR_CODE_BAD_REQUEST);
            return result;
        }
        if (options.containsKey(KEY_OVERRIDE_PACKAGE) || options.containsKey(KEY_OVERRIDE_CERTIFICATE)) {
            String overridePackage = options.getString(KEY_OVERRIDE_PACKAGE, app);
            byte[] overrideCertificateBytes = options.getByteArray(KEY_OVERRIDE_CERTIFICATE);
            CertData overrideCert;
            if (overrideCertificateBytes != null) {
                overrideCert = new CertData(overrideCertificateBytes);
            } else {
                List<CertData> certificates = PackageManagerUtilsKt.getCertificates(context.getPackageManager(), app);
                if (certificates.isEmpty()) {
                    Bundle result = new Bundle();
                    result.putInt(KEY_ERROR_CODE, ERROR_CODE_BAD_REQUEST);
                    return result;
                }
                overrideCert = certificates.get(0);
            }
            if (isPackageOverrideAllowed(account, app, overridePackage, overrideCert)) {
                authManager = new AuthManager(context, account.name, overridePackage, authTokenType);
                authManager.setPackageSignature(PackageManagerUtilsKt.toHexString(PackageManagerUtilsKt.digest(overrideCert, "SHA1"), ""));
            } else {
                Bundle result = new Bundle();
                Intent i = new Intent(context, AskPackageOverrideActivity.class);
                i.putExtra(KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response);
                i.putExtra(KEY_ANDROID_PACKAGE_NAME, app);
                i.putExtra(KEY_ACCOUNT_TYPE, account.type);
                i.putExtra(KEY_ACCOUNT_NAME, account.name);
                i.putExtra(KEY_AUTHTOKEN, authTokenType);
                i.putExtra(KEY_CALLER_UID, options.getInt(KEY_CALLER_UID));
                i.putExtra(KEY_CALLER_PID, options.getInt(KEY_CALLER_PID));
                i.putExtra(KEY_OVERRIDE_PACKAGE, overridePackage);
                i.putExtra(KEY_OVERRIDE_CERTIFICATE, overrideCert.getBytes());
                result.putParcelable(KEY_INTENT, UnpackingRedirectActivity.exposeUniqueIntent(context, i));
                return result;
            }
        } else {
            authManager = new AuthManager(context, account.name, app, authTokenType);
        }
        authManager.setAccountType(account.type);
        try {
            AuthResponse res = requestAuthWithTimeout(authManager, account);
            if (res == null) {
                return errorResult(ERROR_CODE_NETWORK_ERROR, "Authentication failed or timed out");
            }
            if (res.auth != null) {
                Log.d(TAG, "getAuthToken: " + res.auth);
                Bundle result = new Bundle();
                result.putString(KEY_ACCOUNT_TYPE, account.type);
                result.putString(KEY_ACCOUNT_NAME, account.name);
                result.putString(KEY_AUTHTOKEN, res.auth);
                return result;
            } else {
                Bundle result = new Bundle();
                Intent i = new Intent(context, AskPermissionActivity.class);
                i.putExtra(KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response);
                i.putExtra(KEY_ANDROID_PACKAGE_NAME, PackageSpoofUtils.spoofPackageName(context.getPackageManager(), app));
                i.putExtra(KEY_ACCOUNT_TYPE, account.type);
                i.putExtra(KEY_ACCOUNT_NAME, account.name);
                i.putExtra(KEY_AUTHTOKEN, authTokenType);
                i.putExtra(KEY_CALLER_UID, options.getInt(KEY_CALLER_UID));
                i.putExtra(KEY_CALLER_PID, options.getInt(KEY_CALLER_PID));
                i.putExtra(EXTRA_FROM_ACCOUNT_MANAGER, true);
                i.putExtra(AskPermissionActivity.EXTRA_CONSENT_DATA, (byte[]) null);
                try {
                    if (res.consentDataBase64 != null)
                        i.putExtra(AskPermissionActivity.EXTRA_CONSENT_DATA, Base64.decode(res.consentDataBase64, Base64.URL_SAFE));
                } catch (Exception e) {
                    Log.w(TAG, "Can't decode consent data: ", e);
                }
                result.putParcelable(KEY_INTENT, UnpackingRedirectActivity.exposeUniqueIntent(context, i));
                return result;
            }
        } catch (ReauthenticationRequiredException e) {
            Log.w(TAG, "Re-authentication required for account " + account.name, e);
            return createReAuthIntentResult(response, account, options);
        } catch (Exception e) {
            Log.w(TAG, e);
            throw new NetworkErrorException(e);
        }
    }

    private AuthResponse requestAuthWithTimeout(AuthManager authManager, Account account) throws ReauthenticationRequiredException {
        Future<AuthResponse> future = AUTH_EXECUTOR.submit(() -> requestAuthForAccountManager(authManager, account, true));
        try {
            return future.get(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            Log.w(TAG, "Authentication request timed out after " + AUTH_TIMEOUT_SECONDS + " seconds");
            return null;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            Log.w(TAG, "Authentication request was interrupted");
            return null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ReauthenticationRequiredException) {
                throw (ReauthenticationRequiredException) e.getCause();
            }
            Log.w(TAG, "Authentication request failed", e.getCause());
            return null;
        }
    }

    private Bundle errorResult(int code, String message) {
        Bundle result = new Bundle();
        result.putInt(KEY_ERROR_CODE, code);
        if (message != null) result.putString(KEY_ERROR_MESSAGE, message);
        return result;
    }

    private AuthResponse requestAuthForAccountManager(AuthManager authManager, Account account, boolean legacy) throws IOException, ReauthenticationRequiredException {
        try {
            return authManager.requestAuth(legacy);
        } catch (NotOkayException e) {
            if (e.getMessage() != null) {
                Resolution errorResolution = ErrorResolverKt.resolveAuthErrorMessage(context, e.getMessage());
                if (errorResolution instanceof Reauthenticate) {
                    throw new ReauthenticationRequiredException(e);
                }
                if (errorResolution != null) {
                    AuthResponse response = ErrorResolverKt.initiateFromBackgroundBlocking(
                            errorResolution,
                            context,
                            account,
                            () -> authManager.requestAuth(legacy)
                    );
                    if (response == null) throw new IOException(e);
                    return response;
                }
            }
            throw new IOException(e);
        }
    }

    private Bundle createReAuthIntentResult(AccountAuthenticatorResponse response, Account account, Bundle options) {
        Bundle result = new Bundle();
        Intent intent = new Intent(context, LoginActivity.class);
        if (options != null) intent.putExtras(options);
        intent.putExtra(KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response);
        intent.putExtra(KEY_ACCOUNT_TYPE, account.type);
        intent.putExtra(KEY_ACCOUNT_NAME, account.name);
        intent.putExtra(LoginActivity.EXTRA_RE_AUTH_ACCOUNT, account);
        result.putParcelable(KEY_INTENT, intent);
        return result;
    }

    private static class ReauthenticationRequiredException extends Exception {
        ReauthenticationRequiredException(Throwable cause) {
            super(cause);
        }
    }

    @Override
    public String getAuthTokenLabel(String authTokenType) {
        Log.d(TAG, "getAuthTokenLabel: " + authTokenType);
        return null;
    }

    @Override
    public Bundle hasFeatures(AccountAuthenticatorResponse response, Account account, String[] features) throws NetworkErrorException {
        Log.d(TAG, "hasFeatures: " + account + ", " + Arrays.toString(features));
        if (features == null || features.length == 0) {
            Bundle result = new Bundle();
            result.putBoolean(KEY_BOOLEAN_RESULT, true);
            return result;
        }
        AccountManager accountManager = AccountManager.get(context);
        String services = accountManager.getUserData(account, "services");
        boolean res = true;
        if (services != null) {
            List<String> servicesList = Arrays.asList(services.split(","));
            for (String feature : features) {
                if (feature.startsWith("service_") && !servicesList.contains(feature.substring(8))) {
                    Log.d(TAG, "Feature " + feature + " not supported");
                    res = false;
                } else if (!feature.startsWith("service_") && !servicesList.contains(feature)) {
                    Log.d(TAG, "Feature " + feature + " not supported");
                    res = false;
                }
            }
        } else {
            res = false;
        }
        Bundle result = new Bundle();
        result.putBoolean(KEY_BOOLEAN_RESULT, res);
        return result;
    }

    @Override
    public Bundle updateCredentials(AccountAuthenticatorResponse response, Account account, String authTokenType, Bundle options) throws NetworkErrorException {
        Log.d(TAG, "updateCredentials: " + account + ", " + authTokenType + ", " + options);
        return null;
    }
}
