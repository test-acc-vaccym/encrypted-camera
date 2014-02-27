package com.andrewreitz.encryptedcamera.ui.fragment;

import android.app.FragmentManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;

import com.andrewreitz.encryptedcamera.EncryptedCameraApp;
import com.andrewreitz.encryptedcamera.R;
import com.andrewreitz.encryptedcamera.bus.EncryptionEvent;
import com.andrewreitz.encryptedcamera.di.annotation.EncryptedDirectory;
import com.andrewreitz.encryptedcamera.di.annotation.UnlockNotification;
import com.andrewreitz.encryptedcamera.encryption.EncryptionProvider;
import com.andrewreitz.encryptedcamera.encryption.KeyManager;
import com.andrewreitz.encryptedcamera.externalstoreage.ExternalStorageManager;
import com.andrewreitz.encryptedcamera.filesystem.SecureDelete;
import com.andrewreitz.encryptedcamera.sharedpreference.EncryptedCameraPreferenceManager;
import com.andrewreitz.encryptedcamera.ui.activity.BaseActivity;
import com.andrewreitz.encryptedcamera.ui.dialog.ErrorDialog;
import com.andrewreitz.encryptedcamera.ui.dialog.PasswordDialog;
import com.andrewreitz.encryptedcamera.ui.dialog.SetPasswordDialog;
import com.google.common.collect.ImmutableList;
import com.squareup.otto.Bus;
import com.squareup.otto.Subscribe;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jraf.android.backport.switchwidget.SwitchPreference;
import org.mindrot.jbcrypt.BCrypt;

import java.io.File;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;
import java.util.List;

import javax.crypto.SecretKey;
import javax.inject.Inject;

import timber.log.Timber;

import static com.google.common.base.Preconditions.checkNotNull;

public class SettingsHomeFragment extends PreferenceFragment implements
        SetPasswordDialog.SetPasswordDialogListener, Preference.OnPreferenceChangeListener, PasswordDialog.PasswordDialogListener, ErrorDialog.ErrorDialogCallback {

    private static final int NOTIFICATION_ID = 1337;

    @Inject NotificationManager notificationManager;
    @Inject KeyManager keyManager;
    @Inject EncryptedCameraPreferenceManager preferenceManager;
    @Inject @UnlockNotification Notification unlockNotification;
    @Inject @EncryptedDirectory File encryptedDirectory;
    @Inject ExternalStorageManager externalStorageManager;
    @Inject EncryptionProvider encryptionProvider;
    @Inject SecureDelete secureDelete;
    @Inject FragmentManager fragmentManager;
    @Inject Bus bus;

    private SwitchPreference switchPreferenceDecrypt;
    private SwitchPreference switchPreferencePassword;
    private AsyncTask<Void, Void, Boolean> runningTask;

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        BaseActivity.get(this).inject(this);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences_home);
    }

    @Override
    @SuppressWarnings("ConstantConditions")
    public void onResume() {
        super.onResume();
        bus.register(this);
        switchPreferenceDecrypt = (SwitchPreference) findPreference(getString(R.string.pref_key_decrypt));
        switchPreferenceDecrypt.setOnPreferenceChangeListener(this);
        switchPreferencePassword = (SwitchPreference) findPreference(getString(R.string.pref_key_use_password));
        switchPreferencePassword.setOnPreferenceChangeListener(this);
    }

    @Override public void onPause() {
        super.onPause();
        bus.unregister(this);
    }

    @Override public void setRetainInstance(boolean retain) {
        super.setRetainInstance(true);
    }

    @Override
    public void onPasswordSet(String password) {
        // TODO Re-Encrypt all files that were encrypted with the original key
        byte[] salt = new byte[10];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(salt);
        try {
            SecretKey secretKey = keyManager.generateKeyWithPassword(password.toCharArray(), salt);
            encryptionProvider.setSecretKey(secretKey);
            keyManager.saveKey(EncryptedCameraApp.KEY_STORE_ALIAS, secretKey);
            keyManager.saveKeyStore();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | CertificateException | KeyStoreException | IOException e) {
            Timber.e(e, "Error saving encryption key with password");
            ErrorDialog.newInstance(getString(R.string.encryption_error), getString(R.string.error_saving_encryption_key));
            return;
        }
        preferenceManager.setPassword(password);
        preferenceManager.setSalt(salt);
        preferenceManager.setHasPassword(true);
        switchPreferencePassword.setChecked(true);
    }

    @Override public void onPasswordEntered(String password) {
        if (!doPasswordCheck(password)) {
            showIncorrectPasswordDialog();
            return;
        }
        if (!setSecretKey(password)) return;
        decryptToSdDirectory(externalStorageManager.getAppExternalDirectory());
    }

    @Override public void onPasswordCancel() {
        switchPreferenceDecrypt.setChecked(false);
    }

    @Override public void onPasswordSetCancel() {
    }

    @Override public void onErrorDialogDismissed() {
        // TODO Remove All Get Activities and make a manager
        //noinspection ConstantConditions
        getActivity().finish();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        //newValue should always be a boolean but just to be sure
        if (!Boolean.class.isInstance(newValue)) {
            throw new IllegalArgumentException("newValue is not a boolean");
        }

        boolean value = (boolean) newValue;

        //noinspection ConstantConditions
        if (preference.getKey().equals(getString(R.string.pref_key_use_password))) {
            return handleUsePasswordPreference(value);
        } else if (preference.getKey().equals(getString(R.string.pref_key_decrypt))) {
            return handleDecryptedPreference(value);
        }

        throw new RuntimeException("Unknown preference passed in preference == " + preference.getKey());
    }

    @Subscribe public void handleEncryptionEvent(EncryptionEvent event) {
        if (event.state == EncryptionEvent.EncryptionState.ENCRYPTING) {
            showErrorDialog(
                    getString(R.string.error),
                    getString(R.string.error_currently_encrypting),
                    "error_decrypting_in_progress",
                    this
            );
        }
    }

    private boolean handleDecryptedPreference(boolean value) {
        handleDecrypt(value);
        return false;
    }

    private boolean handleUsePasswordPreference(boolean checked) {
        if (preferenceManager.isDecrypted()) {
            // don't allow changing password while photos are decrypted
            showErrorDialog(
                    getString(R.string.error),
                    getString(R.string.error_change_password_while_decrypted),
                    "error_change_password_while_decrypted"
            );
            return false;
        } else if (checked && !preferenceManager.hasPassword()) { // check if a password has already been set do to the filtering done for passwords
            SetPasswordDialog.newInstance(this).show(fragmentManager, "password_dialog");
            return false;
        } else {
            if (preferenceManager.hasPassword()) {
                turnOffPassword();
                return false;
            } else {
                createKeyNoPassword();
                return true;
            }
        }
    }

    private void turnOffPassword() {
        PasswordDialog.newInstance(new PasswordDialog.PasswordDialogListener() {
            // Create custom because one in activity does not meet our needs
            @Override public void onPasswordEntered(String password) {
                if (!doPasswordCheck(password)) {
                    showIncorrectPasswordDialog();
                    return;
                }
                if (!setSecretKey(password)) return;
                if (decryptFilesInternally(password)) return;
                reEncryptFilesInternally();
            }

            @Override public void onPasswordCancel() {
            }
        }).show(fragmentManager, "get_password_dialog");
    }

    private void reEncryptFilesInternally() {
        try {
            //noinspection ConstantConditions
            for (File in : encryptedDirectory.listFiles()) {
                File out = new File(in.getPath().replace(".tmp", ""));
                encryptionProvider.encrypt(in, out);
                //noinspection ResultOfMethodCallIgnored
                in.delete();
            }
            switchPreferencePassword.setChecked(false);
        } catch (InvalidKeyException | IOException | InvalidAlgorithmParameterException e) {
            Timber.w(e, "Error encrypting files without a password");
            // if this exception really happens the application won't work
            // We should really crash.
            throw new RuntimeException(e);
        }
    }

    private boolean decryptFilesInternally(@NotNull String password) {
        if (!doPasswordCheck(password)) {
            if (!doPasswordCheck(password)) {
                showIncorrectPasswordDialog();
                return false;
            }
        }

        try {
            //noinspection ConstantConditions
            for (File in : encryptedDirectory.listFiles()) {
                File out = new File(encryptedDirectory, in.getName() + ".tmp");
                encryptionProvider.decrypt(in, out);
                //noinspection ResultOfMethodCallIgnored
                in.delete();
            }
        } catch (InvalidKeyException | IOException | InvalidAlgorithmParameterException e) {
            Timber.w(e, "error unencrypting internally");
            showErrorDialog(
                    getString(R.string.error),
                    getString(R.string.error_incorrect_password),
                    "error_dialog_removing_password"
            );
            return true;
        }

        return false;
    }

    private boolean createKeyNoPassword() {
        // Create a keystore for encryption that does not require a password
        try {
            SecretKey secretKey = keyManager.generateKeyNoPassword();
            encryptionProvider.setSecretKey(secretKey);
            keyManager.saveKey(EncryptedCameraApp.KEY_STORE_ALIAS, secretKey);
            keyManager.saveKeyStore();
            return true;
        } catch (NoSuchAlgorithmException | KeyStoreException | IOException | CertificateException e) {
            // The app really wouldn't work at this point
            Timber.e(e, "Error saving encryption key, no password");
            showErrorDialog(
                    getString(R.string.encryption_error),
                    getString(R.string.error_saving_encryption_key),
                    "error_dialog_generate_key_no_password"
            );
        }

        return false;
    }

    private void handleDecrypt(boolean decrypt) {
        File appExternalDirectory = externalStorageManager.getAppExternalDirectory();

        if (appExternalDirectory == null || !externalStorageManager.checkSdCardIsInReadAndWriteState()) {
            //noinspection ConstantConditions
            showErrorDialog(
                    getString(R.string.error),
                    getString(R.string.error_sdcard_message),
                    "error_dialog_sdcard"
            );
            return;
        }

        if (decrypt) {
            if (preferenceManager.hasPassword()) {
                PasswordDialog passwordDialog = PasswordDialog.newInstance(this);
                //noinspection ConstantConditions
                passwordDialog.show(fragmentManager, "dialog_get_password");
            } else {
                decryptToSdDirectory(appExternalDirectory);
            }
        } else {
            encryptSdDirectory(appExternalDirectory);
        }
    }

    private void decryptToSdDirectory(@NotNull File appExternalDirectory) {
        this.decryptToSdDirectory(appExternalDirectory, null);
    }

    private void decryptToSdDirectory(@NotNull File appExternalDirectory, @Nullable String password) {
        if (password != null && !doPasswordCheck(password)) {
            showIncorrectPasswordDialog();
            return;
        }

        //noinspection ConstantConditions
        runningTask = new DecryptFilesTask(
                appExternalDirectory,
                getActivity(),
                ImmutableList.copyOf(encryptedDirectory.listFiles())
        );
        //noinspection unchecked
        runningTask.execute();
    }

    private void showIncorrectPasswordDialog() {
        showErrorDialog(
                getString(R.string.error),
                getString(R.string.error_incorrect_password),
                "error_dialog_encrypt_pw"
        );
    }

    private void encryptSdDirectory(File appExternalDirectory) {
        this.notificationManager.cancel(NOTIFICATION_ID);
        //noinspection ConstantConditions
        for (File unencrypted : appExternalDirectory.listFiles()) {
            File encrypted = new File(encryptedDirectory, unencrypted.getName());
            try {
                encryptionProvider.encrypt(unencrypted, encrypted);
                secureDelete.secureDelete(unencrypted);
            } catch (IOException | InvalidKeyException | InvalidAlgorithmParameterException e) {
                Timber.d(e, "unable to encrypt and move file to internal storage");
                showErrorDialog(
                        getString(R.string.error),
                        String.format(getString(R.string.error_reencrypting), unencrypted.getPath()),
                        "error_dialog_re_encrypt"
                );
            }
        }

        switchPreferenceDecrypt.setChecked(false);
    }

    private boolean setSecretKey(String password) {
        try {
            // recreate the secret key and give it to the encryption provider
            SecretKey key = keyManager.generateKeyWithPassword(password.toCharArray(), preferenceManager.getSalt());
            encryptionProvider.setSecretKey(key);
            return true;
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            Timber.w(e, "Error recreating secret key.  This probably should never happen");
            showErrorDialog(
                    getString(R.string.error),
                    getString(R.string.error_terrible),
                    "error_dialog_recreate_key"
            );
        }

        return false;
    }

    private void showErrorDialog(String error, String message, String tag) {
        showErrorDialog(error, message, tag, null);
    }

    private void showErrorDialog(
            @Nullable String error,
            @Nullable String message,
            @Nullable String tag,
            @Nullable ErrorDialog.ErrorDialogCallback callback
    ) {
        ErrorDialog errorDialog = ErrorDialog.newInstance(
                error,
                message
        );

        if (callback != null) {
            errorDialog.setCallback(callback);
        }

        errorDialog.show(fragmentManager, tag);
    }

    private boolean doPasswordCheck(@NotNull String password) {
        String passwordHash = preferenceManager.getPasswordHash();
        return BCrypt.checkpw(password, passwordHash);
    }

    private static abstract class AbstractFilesTask extends AsyncTask<Void, Void, Boolean> {

        private final Context context;
        private final List<File> files;

        private ProgressDialog progressDialog;

        AbstractFilesTask(@NotNull Context context, @NotNull List<File> files) {
            this.context = checkNotNull(context);
            this.files = checkNotNull(files);
        }

        @Override final protected void onPreExecute() {
            progressDialog = new ProgressDialog(context);
            getProgressDialog().setCancelable(true);
            getProgressDialog().setMessage("Decrypting Files");
            getProgressDialog().setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            getProgressDialog().setProgress(0);
            getProgressDialog().setMax(getFiles().size());
            getProgressDialog().show();
        }

        @Override final protected void onProgressUpdate(Void... values) {
            getProgressDialog().setProgress(1 + getProgressDialog().getProgress());
        }

        public Context getContext() {
            return context;
        }

        public List<File> getFiles() {
            return files;
        }

        public ProgressDialog getProgressDialog() {
            return progressDialog;
        }
    }

    private final class DecryptFilesTask extends AbstractFilesTask {

        private final File appExternalDirectory;

        DecryptFilesTask(@NotNull File appExternalDirectory, @NotNull Context context, @NotNull List<File> files) {
            super(context, files);
            this.appExternalDirectory = checkNotNull(appExternalDirectory);
        }

        @Override protected Boolean doInBackground(Void... params) {

            boolean errorShown = false;
            //noinspection ConstantConditions
            for (File encrypted : getFiles()) {
                File unencrypted = new File(appExternalDirectory, encrypted.getName());
                try {
                    encryptionProvider.decrypt(encrypted, unencrypted);
                    //noinspection ResultOfMethodCallIgnored
                    encrypted.delete();
                } catch (InvalidKeyException | IOException | InvalidAlgorithmParameterException e) {
                    Timber.d(e, "unable to decrypt and move file %s to sdcard", encrypted.getPath());
                    // Deleted the file that was put on the sdcard and was not the full file
                    //noinspection ResultOfMethodCallIgnored
                    unencrypted.delete();
                    errorShown = true;
                }
            }

            return errorShown;
        }

        @Override protected void onPostExecute(Boolean errorShown) {
            getProgressDialog().dismiss();

            if (isCancelled()) {
                return;
            }

            // Error not shown display the notification
            if (!errorShown) {
                notificationManager.notify(
                        NOTIFICATION_ID,
                        unlockNotification
                );
                switchPreferenceDecrypt.setChecked(true);
            } else {
                // there was an error reset the switch preferences
                switchPreferenceDecrypt.setChecked(false);
            }
        }
    }
}
