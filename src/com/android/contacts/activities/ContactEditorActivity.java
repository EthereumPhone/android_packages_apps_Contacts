/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.contacts.activities;

import android.app.Dialog;
import android.app.FragmentTransaction;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract.QuickContact;
import androidx.appcompat.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import android.content.ContentResolver;
import android.provider.ContactsContract.Data;
import android.content.Context;
import android.app.Activity;
import android.os.StrictMode;
import android.widget.LinearLayout;
import android.widget.EditText;
import com.android.contacts.editor.StructuredNameEditorView;
import com.android.contacts.editor.KindSectionView;
import android.text.Editable;
import android.text.TextWatcher;

import org.json.JSONObject;
import org.kethereum.eip137.model.ENSName;
import org.kethereum.ens.ENS;
import org.kethereum.rpc.EthereumRPC;
import org.kethereum.rpc.HttpEthereumRPC;
import android.view.ViewGroup;
import okhttp3.*;

import com.android.contacts.AppCompatContactsActivity;
import com.android.contacts.ContactSaveService;
import com.android.contacts.DynamicShortcuts;
import com.android.contacts.R;
import com.android.contacts.detail.PhotoSelectionHandler;
import com.android.contacts.editor.ContactEditorFragment;
import com.android.contacts.editor.EditorIntents;
import com.android.contacts.editor.PhotoSourceDialogFragment;
import com.android.contacts.interactions.ContactDeletionInteraction;
import com.android.contacts.model.RawContactDeltaList;
import com.android.contacts.util.DialogManager;
import com.android.contacts.util.ImplicitIntentsUtil;

import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Contact editor with only the most important fields displayed initially.
 */
public class ContactEditorActivity extends AppCompatContactsActivity implements
        PhotoSourceDialogFragment.Listener,
        DialogManager.DialogShowingViewActivity {
    private static final String TAG = "ContactEditorActivity";

    public static final String ACTION_JOIN_COMPLETED = "joinCompleted";
    public static final String ACTION_SAVE_COMPLETED = "saveCompleted";

    public static final int RESULT_CODE_SPLIT = 2;
    // 3 used for ContactDeletionInteraction.RESULT_CODE_DELETED
    public static final int RESULT_CODE_EDITED = 4;

    /**
     * The contact will be saved to this account when this is set for an insert. This
     * is necessary because {@link android.accounts.Account} cannot be created with null values
     * for the name and type and an Account is needed for
     * {@link android.provider.ContactsContract.Intents.Insert#EXTRA_ACCOUNT}
     */
    public static final String EXTRA_ACCOUNT_WITH_DATA_SET =
            "com.android.contacts.ACCOUNT_WITH_DATA_SET";

    private static final String TAG_EDITOR_FRAGMENT = "editor_fragment";

    private static final String STATE_PHOTO_MODE = "photo_mode";
    private static final String STATE_ACTION_BAR_TITLE = "action_bar_title";
    private static final String STATE_PHOTO_URI = "photo_uri";

    /**
     * Boolean intent key that specifies that this activity should finish itself
     * (instead of launching a new view intent) after the editor changes have been
     * saved.
     */
    public static final String INTENT_KEY_FINISH_ACTIVITY_ON_SAVE_COMPLETED =
            "finishActivityOnSaveCompleted";

    /**
     * Contract for contact editors Fragments that are managed by this Activity.
     */
    public interface ContactEditor {

        /**
         * Modes that specify what the AsyncTask has to perform after saving
         */
        interface SaveMode {
            /**
             * Close the editor after saving
             */
            int CLOSE = 0;

            /**
             * Reload the data so that the user can continue editing
             */
            int RELOAD = 1;

            /**
             * Split the contact after saving
             */
            int SPLIT = 2;

            /**
             * Join another contact after saving
             */
            int JOIN = 3;

            /**
             * Navigate to the editor view after saving.
             */
            int EDITOR = 4;
        }

        /**
         * The status of the contact editor.
         */
        interface Status {
            /**
             * The loader is fetching data
             */
            int LOADING = 0;

            /**
             * Not currently busy. We are waiting for the user to enter data
             */
            int EDITING = 1;

            /**
             * The data is currently being saved. This is used to prevent more
             * auto-saves (they shouldn't overlap)
             */
            int SAVING = 2;

            /**
             * Prevents any more saves. This is used if in the following cases:
             * - After Save/Close
             * - After Revert
             * - After the user has accepted an edit suggestion
             * - After the user chooses to expand the editor
             */
            int CLOSING = 3;

            /**
             * Prevents saving while running a child activity.
             */
            int SUB_ACTIVITY = 4;
        }

        /**
         * Sets the hosting Activity that will receive callbacks from the contact editor.
         */
        void setListener(ContactEditorFragment.Listener listener);

        /**
         * Initialize the contact editor.
         */
        void load(String action, Uri lookupUri, Bundle intentExtras);

        /**
         * Applies extras from the hosting Activity to the writable raw contact.
         */
        void setIntentExtras(Bundle extras);

        /**
         * Saves or creates the contact based on the mode, and if successful
         * finishes the activity.
         */
        boolean save(int saveMode);

        /**
         * If there are no unsaved changes, just close the editor, otherwise the user is prompted
         * before discarding unsaved changes.
         */
        boolean revert();

        /**
         * Invoked after the contact is saved.
         */
        void onSaveCompleted(boolean hadChanges, int saveMode, boolean saveSucceeded,
                Uri contactLookupUri, Long joinContactId);

        /**
         * Invoked after the contact is joined.
         */
        void onJoinCompleted(Uri uri);
    }

    /**
     * Displays a PopupWindow with photo edit options.
     */
    private final class EditorPhotoSelectionHandler extends PhotoSelectionHandler {

        /**
         * Receiver of photo edit option callbacks.
         */
        private final class EditorPhotoActionListener extends PhotoActionListener {

            @Override
            public void onRemovePictureChosen() {
                getEditorFragment().removePhoto();
            }

            @Override
            public void onPhotoSelected(Uri uri) throws FileNotFoundException {
                mPhotoUri = uri;
                getEditorFragment().updatePhoto(uri);

                // Re-create the photo handler the next time we need it so that additional photo
                // selections create a new temp file (and don't hit the one that was just added
                // to the cache).
                mPhotoSelectionHandler = null;
            }

            @Override
            public Uri getCurrentPhotoUri() {
                return mPhotoUri;
            }

            @Override
            public void onPhotoSelectionDismissed() {
            }
        }

        private final EditorPhotoActionListener mPhotoActionListener;

        public EditorPhotoSelectionHandler(int photoMode) {
            // We pass a null changeAnchorView since we are overriding onClick so that we
            // can show the photo options in a dialog instead of a ListPopupWindow (which would
            // be anchored at changeAnchorView).

            // TODO: empty raw contact delta list
            super(ContactEditorActivity.this, /* changeAnchorView =*/ null, photoMode,
                    /* isDirectoryContact =*/ false, new RawContactDeltaList());
            mPhotoActionListener = new EditorPhotoActionListener();
        }

        @Override
        public PhotoActionListener getListener() {
            return mPhotoActionListener;
        }

        @Override
        protected void startPhotoActivity(Intent intent, int requestCode, Uri photoUri) {
            mPhotoUri = photoUri;
            startActivityForResult(intent, requestCode);
        }
    }

    private int mActionBarTitleResId;
    private ContactEditor mFragment;
    private Toolbar mToolbar;
    private boolean mFinishActivityOnSaveCompleted;
    private DialogManager mDialogManager = new DialogManager(this);

    private EditorPhotoSelectionHandler mPhotoSelectionHandler;
    private Uri mPhotoUri;
    private int mPhotoMode;

    private final ContactEditorFragment.Listener  mFragmentListener =
            new ContactEditorFragment.Listener() {

                @Override
                public void onDeleteRequested(Uri contactUri) {
                    ContactDeletionInteraction.start(
                            ContactEditorActivity.this, contactUri, true);
                }

                @Override
                public void onReverted() {
                    finish();
                }

                @Override
                public void onSaveFinished(Intent resultIntent) {
                    if (mFinishActivityOnSaveCompleted) {
                        setResult(resultIntent == null ? RESULT_CANCELED : RESULT_OK, resultIntent);
                    } else if (resultIntent != null) {
                        ImplicitIntentsUtil.startActivityInApp(
                                ContactEditorActivity.this, resultIntent);
                    }
                    finish();
                }

                @Override
                public void onContactSplit(Uri newLookupUri) {
                    setResult(RESULT_CODE_SPLIT, /* data */ null);
                    finish();
                }

                @Override
                public void onContactNotFound() {
                    finish();
                }

                @Override
                public void onEditOtherRawContactRequested(
                        Uri contactLookupUri, long rawContactId, ArrayList<ContentValues> values) {
                    final Intent intent = EditorIntents.createEditOtherRawContactIntent(
                            ContactEditorActivity.this, contactLookupUri, rawContactId, values);
                    ImplicitIntentsUtil.startActivityInApp(
                            ContactEditorActivity.this, intent);
                    finish();
                }
            };

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);

        RequestPermissionsActivity.startPermissionActivityIfNeeded(this);

        final Intent intent = getIntent();
        final String action = intent.getAction();

        // Update the component name of our intent to be this class to clear out any activity
        // aliases. Otherwise ContactSaveService won't notify this activity once a save is finished.
        // See b/34154706 for more info.
        intent.setComponent(new ComponentName(this, ContactEditorActivity.class));

        // Determine whether or not this activity should be finished after the user is done
        // editing the contact or if this activity should launch another activity to view the
        // contact's details.
        mFinishActivityOnSaveCompleted = intent.getBooleanExtra(
                INTENT_KEY_FINISH_ACTIVITY_ON_SAVE_COMPLETED, false);

        // The only situation where action could be ACTION_JOIN_COMPLETED is if the
        // user joined the contact with another and closed the activity before
        // the save operation was completed.  The activity should remain closed then.
        if (ACTION_JOIN_COMPLETED.equals(action)) {
            finish();
            return;
        }

        if (ACTION_SAVE_COMPLETED.equals(action)) {
            finish();
            return;
        }

        setContentView(R.layout.contact_editor_activity);
        mToolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(mToolbar);
        if (Intent.ACTION_EDIT.equals(action)) {
            mActionBarTitleResId = R.string.contact_editor_title_existing_contact;
        } else {
            mActionBarTitleResId = R.string.contact_editor_title_new_contact;
        }
        mToolbar.setTitle(mActionBarTitleResId);
        // Set activity title for Talkback
        setTitle(mActionBarTitleResId);

        Button qrCodeButton = findViewById(R.id.qr_code_button);
        final Context context = this;
        qrCodeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                IntentIntegrator integrator = new IntentIntegrator((Activity) context);
                integrator.setPrompt("Scan an ethereum address QR code");
                integrator.setOrientationLocked(false);
                integrator.setBeepEnabled(false);
                integrator.initiateScan();
            }
        });


        mFragment =
            (ContactEditor) getFragmentManager().findFragmentById(R.id.contact_editor_fragment);

        if (savedState != null) {
            // Restore state
            mPhotoMode = savedState.getInt(STATE_PHOTO_MODE);
            mActionBarTitleResId = savedState.getInt(STATE_ACTION_BAR_TITLE);
            mPhotoUri = Uri.parse(savedState.getString(STATE_PHOTO_URI));

            mToolbar.setTitle(mActionBarTitleResId);
        }

        // Set listeners
        mFragment.setListener(mFragmentListener);

        // Load editor data (even if it's hidden)
        final Uri uri = Intent.ACTION_EDIT.equals(action) ? getIntent().getData() : null;
        mFragment.load(action, uri, getIntent().getExtras());

        if (Intent.ACTION_INSERT.equals(action)) {
            DynamicShortcuts.reportShortcutUsed(this, DynamicShortcuts.SHORTCUT_ADD_CONTACT);
        }


        Runnable run = new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                System.out.println("Looking for ethAddress EditText");
                EditText editText = findEditTextByHint(findViewById(R.id.kind_editors), getString(R.string.ethAddress));
                if (editText != null) {
                    System.out.println("Found ethAddress EditText");
                    editText.addTextChangedListener(new TextWatcher() {

                        public void afterTextChanged(Editable s) {
                            // Check if the text is an ENS domain
                            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();

                            StrictMode.setThreadPolicy(policy);

                            String text = s.toString();
                            text = text.replace(" ", "").toLowerCase();
                            try {
                                if (text.endsWith(".eth")) {
                                    // Send https request to https://ensdata.net/ENS, where "ENS" is the ENS domain
                                    // Get the response and set the text to the response
                                    String url = "https://ensdata.net/" + text;
                                    System.out.println("Sending request to " + url);
                                    OkHttpClient client = new OkHttpClient();
                                    Request request = new Request.Builder()
                                            .url(url)
                                            .build();
                                    Response response = client.newCall(request).execute();
                                    String responseText = response.body().string();
                                    System.out.println("Response: " + responseText);
                                    JSONObject jsonObject = new JSONObject(responseText);
                                    if (jsonObject.has("email")) {
                                        findEditTextByHint(findViewById(R.id.kind_section_views), getString(R.string.emailLabelsGroup)).setText(jsonObject.getString("email"));
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                                System.out.println("Error: " + e.getMessage());
                            }
                        }

                        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                        public void onTextChanged(CharSequence s, int start, int before, int count) {}
                    });
                } else {
                    System.out.println("Did not find ethAddress EditText");
                    // Make a loop to check if the editText content is changed
                    /*
                    while (true) {
                        System.out.println("TRYING TO GET THE TEXT");
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        String text = editText.getText().toString();
                        text = text.replace(" ", "").toLowerCase();
                        System.out.println("Checking text: " + text);
                        try {
                            if (text.endsWith(".eth")) {
                                ENS ens = new ENS(new HttpEthereumRPC("https://cloudflare-eth.com", null), null);
                                System.out.println("Checking ENS domain: " + Class.forName("org.kethereum.eip137.model.ENSName").getDeclaredMethods());
                                //ENSName name = new ENSName(text);
                                //String email = ens.getEmail(name);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            System.out.println("Error: " + e.getMessage());
                        }
                    }

                     */
                }
            }
        };

        new Thread(run).start();

        /*
        // Add ontext listener
        LinearLayout mKindSectionViews = findViewById(R.id.kind_section_views);
        final int childCount = mKindSectionViews.getChildCount();
        // Recursively Check all children if it is a EditText, and if the hint is R.id.ethAddress

        for (int i = 0; i < childCount; i++) {
            View child = mKindSectionViews.getChildAt(i);
            // Also check all children of the child
            if (child instanceof LinearLayout) {
                LinearLayout editors = ((LinearLayout) child).findViewById(R.id.editors);
                if (editors != null) {
                    final int childCount3 = editors.getChildCount();
                    for (int k = 0; k < childCount3; k++) {
                        View child3 = editors.getChildAt(k);
                        if (child3 instanceof EditText) {
                            EditText editText = (EditText) child3;
                            if (editText.getHint().equals(getString(R.string.ethAddress))) {
                                editText.addTextChangedListener(new TextWatcher() {

                                    public void afterTextChanged(Editable s) {
                                        // Check if the text is an ENS domain
                                        String text = s.toString();
                                        text = text.replace(" ", "").toLowerCase();
                                        try {
                                            if (text.endsWith(".eth")) {
                                                ENS ens = new ENS(new HttpEthereumRPC("https://cloudflare-eth.com", null), null);
                                                System.out.println("Checking ENS domain: " + Class.forName("org.kethereum.eip137.model.ENSName").getDeclaredMethods());
                                                //ENSName name = new ENSName(text);
                                                //String email = ens.getEmail(name);
                                            }
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                            System.out.println("Error: " + e.getMessage());
                                        }
                                    }
                        
                                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                        
                                    public void onTextChanged(CharSequence s, int start, int before, int count) {}
                                });
                                break;
                            }
                        }
                    }
                }
            }
        }

         */
    }

    public EditText findEditTextByHint(View view, String searchHint) {
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;

            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View childView = viewGroup.getChildAt(i);
                EditText editText = findEditTextByHint(childView, searchHint);

                if (editText != null) {
                    // Found the EditText with the search hint
                    return editText;
                }
            }
        } else if (view instanceof EditText) {
            EditText editText = (EditText) view;

            if (editText.getHint().toString().equals(searchHint)) {
                // Found the EditText with the search hint
                return editText;
            }
        }

        // EditText with the search hint not found in this view or its children
        return null;
    }


    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (mFragment == null) {
            return;
        }

        final String action = intent.getAction();
        if (Intent.ACTION_EDIT.equals(action)) {
            mFragment.setIntentExtras(intent.getExtras());
        } else if (ACTION_SAVE_COMPLETED.equals(action)) {
            mFragment.onSaveCompleted(true,
                    intent.getIntExtra(ContactEditorFragment.SAVE_MODE_EXTRA_KEY,
                            ContactEditor.SaveMode.CLOSE),
                    intent.getBooleanExtra(ContactSaveService.EXTRA_SAVE_SUCCEEDED, false),
                    intent.getData(),
                    intent.getLongExtra(ContactEditorFragment.JOIN_CONTACT_ID_EXTRA_KEY, -1));
        } else if (ACTION_JOIN_COMPLETED.equals(action)) {
            mFragment.onJoinCompleted(intent.getData());
        }
    }

    @Override
    protected Dialog onCreateDialog(int id, Bundle args) {
        if (DialogManager.isManagedId(id)) return mDialogManager.onCreateDialog(id, args);

        // Nobody knows about the Dialog
        Log.w(TAG, "Unknown dialog requested, id: " + id + ", args: " + args);
        return null;
    }

    @Override
    public DialogManager getDialogManager() {
        return mDialogManager;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PHOTO_MODE, mPhotoMode);
        outState.putInt(STATE_ACTION_BAR_TITLE, mActionBarTitleResId);
        outState.putString(STATE_PHOTO_URI,
                mPhotoUri != null ? mPhotoUri.toString() : Uri.EMPTY.toString());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == IntentIntegrator.REQUEST_CODE && resultCode == RESULT_OK) {
            IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
            if (result != null && result.getContents() != null) {
                String qrCodeResult = result.getContents();
                if (qrCodeResult.startsWith("ethereum:")) {
                    qrCodeResult = qrCodeResult.substring(9);
                    if (qrCodeResult.contains("@")) {
                        qrCodeResult = qrCodeResult.split("@")[0];
                    }
                }
                // Use the qrCodeResult as needed
                LinearLayout mKindSectionViews = findViewById(R.id.kind_section_views);
                final int childCount = mKindSectionViews.getChildCount();
                // Recursively Check all children if it is a EditText, and if the hint is R.id.ethAddress
                for (int i = 0; i < childCount; i++) {
                    View child = mKindSectionViews.getChildAt(i);
                    // Also check all children of the child
                    if (child instanceof LinearLayout) {
                        LinearLayout editors = ((LinearLayout) child).findViewById(R.id.editors);
                        if (editors != null) {
                            final int childCount3 = editors.getChildCount();
                            for (int k = 0; k < childCount3; k++) {
                                View child3 = editors.getChildAt(k);
                                if (child3 instanceof EditText) {
                                    EditText editText = (EditText) child3;
                                    if (editText.getHint().equals(getString(R.string.ethAddress))) {
                                        editText.setText(qrCodeResult);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }

            }
        } else {
            if (mPhotoSelectionHandler == null) {
                mPhotoSelectionHandler = (EditorPhotoSelectionHandler) getPhotoSelectionHandler();
            }
            if (mPhotoSelectionHandler.handlePhotoActivityResult(requestCode, resultCode, data)) {
                return;
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    public void changeTextOfEntity(String newText, String hint) {
        LinearLayout mKindSectionViews = findViewById(R.id.kind_section_views);
        final int childCount = mKindSectionViews.getChildCount();
        // Recursively Check all children if it is a EditText, and if the hint is R.id.ethAddress
        for (int i = 0; i < childCount; i++) {
            View child = mKindSectionViews.getChildAt(i);
            // Also check all children of the child
            if (child instanceof LinearLayout) {
                LinearLayout editors = ((LinearLayout) child).findViewById(R.id.editors);
                if (editors != null) {
                    final int childCount3 = editors.getChildCount();
                    for (int k = 0; k < childCount3; k++) {
                        View child3 = editors.getChildAt(k);
                        if (child3 instanceof EditText) {
                            EditText editText = (EditText) child3;
                            if (editText.getHint().equals(hint)) {
                                editText.setText(newText);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    public void setEditTextToNewText(ViewGroup layout, String hint, String newText) {
        for (int i = 0; i < layout.getChildCount(); i++) {
            View child = layout.getChildAt(i);
            if (child instanceof EditText) {
                EditText editText = (EditText) child;
                if (editText.getHint().equals(hint)) {
                    editText.setText(newText);
                }
            } else if (child instanceof ViewGroup) {
                setEditTextToNewText((ViewGroup) child, hint, newText);
            }
        }
    }

    public EditText getEditTextFromHint(ViewGroup layout, String hint) {
        for (int i = 0; i < layout.getChildCount(); i++) {
            View child = layout.getChildAt(i);
            if (child instanceof EditText) {
                EditText editText = (EditText) child;
                if (editText.getHint().equals(hint)) {
                    return editText;
                }
            } else if (child instanceof ViewGroup) {
                return getEditTextFromHint((ViewGroup) child, hint);
            }
        }
        return null;
    }
    

    @Override
    public void onBackPressed() {
        if (mFragment != null) {
            mFragment.revert();
        }
    }

    /**
     * Opens a dialog showing options for the user to change their photo (take, choose, or remove
     * photo).
     */
    public void changePhoto(int photoMode) {
        mPhotoMode = photoMode;
        // This method is called from an onClick handler in the PhotoEditorView. It's possible for
        // onClick methods to run after onSaveInstanceState is called for the activity, so check
        // if it's safe to commit transactions before trying.
        if (isSafeToCommitTransactions()) {
            PhotoSourceDialogFragment.show(this, mPhotoMode);
        }
    }

    public Toolbar getToolbar() {
        return mToolbar;
    }

    @Override
    public void onRemovePictureChosen() {
        getPhotoSelectionHandler().getListener().onRemovePictureChosen();
    }

    @Override
    public void onTakePhotoChosen() {
        getPhotoSelectionHandler().getListener().onTakePhotoChosen();
    }

    @Override
    public void onPickFromGalleryChosen() {
        getPhotoSelectionHandler().getListener().onPickFromGalleryChosen();
    }

    private PhotoSelectionHandler getPhotoSelectionHandler() {
        if (mPhotoSelectionHandler == null) {
            mPhotoSelectionHandler = new EditorPhotoSelectionHandler(mPhotoMode);
        }
        return mPhotoSelectionHandler;
    }

    private ContactEditorFragment getEditorFragment() {
        return (ContactEditorFragment) mFragment;
    }
}
