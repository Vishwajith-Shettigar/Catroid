/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2018 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://developer.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.catrobat.catroid.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AlertDialog;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import org.catrobat.catroid.BuildConfig;
import org.catrobat.catroid.ProjectManager;
import org.catrobat.catroid.R;
import org.catrobat.catroid.cast.CastManager;
import org.catrobat.catroid.common.Constants;
import org.catrobat.catroid.content.Project;
import org.catrobat.catroid.io.StorageOperations;
import org.catrobat.catroid.io.ZipArchiver;
import org.catrobat.catroid.io.asynctask.ProjectImportTask;
import org.catrobat.catroid.io.asynctask.ProjectLoadTask;
import org.catrobat.catroid.io.asynctask.ProjectSaveTask;
import org.catrobat.catroid.stage.StageActivity;
import org.catrobat.catroid.ui.dialogs.TermsOfUseDialogFragment;
import org.catrobat.catroid.ui.recyclerview.dialog.AboutDialogFragment;
import org.catrobat.catroid.ui.recyclerview.dialog.PrivacyPolicyDialogFragment;
import org.catrobat.catroid.ui.recyclerview.fragment.MainMenuFragment;
import org.catrobat.catroid.ui.runtimepermissions.RequiresPermissionTask;
import org.catrobat.catroid.ui.settingsfragments.SettingsFragment;
import org.catrobat.catroid.utils.FileMetaDataExtractor;
import org.catrobat.catroid.utils.ScreenValueHandler;
import org.catrobat.catroid.utils.ToastUtil;
import org.catrobat.catroid.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

import static org.catrobat.catroid.common.Constants.BACKPACK_DIRECTORY;
import static org.catrobat.catroid.common.Constants.CODE_XML_FILE_NAME;
import static org.catrobat.catroid.common.Constants.TMP_DIR_NAME;
import static org.catrobat.catroid.common.FlavoredConstants.DEFAULT_ROOT_DIRECTORY;
import static org.catrobat.catroid.common.FlavoredConstants.EXTERNAL_STORAGE_ROOT_DIRECTORY;
import static org.catrobat.catroid.common.SharedPreferenceKeys.AGREED_TO_PRIVACY_POLICY_PREFERENCE_KEY;
import static org.catrobat.catroid.common.SharedPreferenceKeys.SHOW_COPY_PROJECTS_FROM_EXTERNAL_STORAGE_DIALOG;

public class MainMenuActivity extends BaseCastActivity implements
		ProjectLoadTask.ProjectLoadListener {

	public static final String TAG = MainMenuActivity.class.getSimpleName();
	public static final int REQUEST_PERMISSIONS_MOVE_TO_INTERNAL_STORAGE = 501;

	private ProjectImportTask.ProjectImportListener projectCopyListener = success -> {
		loadFragment();
		setShowProgressBar(false);
		if (success) {
			ToastUtil.showSuccess(this, R.string.projects_successful_copied_toast);
		} else {
			ToastUtil.showError(this, R.string.error_during_copying_projects_toast);
		}
	};

	private ProjectImportTask.ProjectImportListener projectMoveListener = success -> {
		loadFragment();
		setShowProgressBar(false);
		if (success) {
			ToastUtil.showSuccess(this, R.string.projects_successful_moved_toast);
			try {
				StorageOperations.deleteDir(EXTERNAL_STORAGE_ROOT_DIRECTORY);
			} catch (IOException e) {
				Log.e(TAG, "Cannot delete dir in external storage after import", e);
			}
		} else {
			ToastUtil.showError(this, R.string.error_during_copying_projects_toast);
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		SettingsFragment.setToChosenLanguage(this);

		PreferenceManager.setDefaultValues(this, R.xml.preferences, true);
		ScreenValueHandler.updateScreenWidthAndHeight(this);

		boolean hasUserAgreedToPrivacyPolicy = PreferenceManager.getDefaultSharedPreferences(this)
				.getBoolean(AGREED_TO_PRIVACY_POLICY_PREFERENCE_KEY, false);

		if (hasUserAgreedToPrivacyPolicy) {
			loadContent();
		} else {
			setContentView(R.layout.privacy_policy_view);
		}
	}

	public void handleAgreedToPrivacyPolicyButton(View view) {
		PreferenceManager.getDefaultSharedPreferences(this)
				.edit()
				.putBoolean(AGREED_TO_PRIVACY_POLICY_PREFERENCE_KEY, true)
				.commit();
		loadContent();
	}

	public void handleDeclinedPrivacyPolicyButton(View view) {
		View dialogView = View.inflate(this, R.layout.declined_privacy_agreement_alert_view, null);

		String linkString = getString(R.string.about_link_template,
				Constants.CATROBAT_ABOUT_URL,
				getString(R.string.share_website_text));
		TextView linkTextView = dialogView.findViewById(R.id.share_website_view);
		linkTextView.setText(Html.fromHtml(linkString));

		new AlertDialog.Builder(this)
				.setView(dialogView)
				.setNeutralButton(R.string.ok, null)
				.show();
	}

	private void loadContent() {
		if (BuildConfig.FEATURE_APK_GENERATOR_ENABLED) {
			setContentView(R.layout.activity_main_menu_splashscreen);
			prepareStandaloneProject();
			return;
		}

		setContentView(R.layout.activity_main_menu);
		setSupportActionBar(findViewById(R.id.toolbar));
		getSupportActionBar().setIcon(R.drawable.pc_toolbar_icon);
		getSupportActionBar().setTitle(R.string.app_name);

		setShowProgressBar(true);

		if (SettingsFragment.isCastSharedPreferenceEnabled(this)) {
			CastManager.getInstance().initializeCast(this);
		}

		boolean checkForProjectsInExternalStorage = PreferenceManager.getDefaultSharedPreferences(this)
				.getBoolean(SHOW_COPY_PROJECTS_FROM_EXTERNAL_STORAGE_DIALOG, true);

		if (checkForProjectsInExternalStorage) {
			new RequiresPermissionTask(REQUEST_PERMISSIONS_MOVE_TO_INTERNAL_STORAGE,
					Arrays.asList(WRITE_EXTERNAL_STORAGE, READ_EXTERNAL_STORAGE),
					R.string.runtime_permission_general) {
				public void task() {
					if (sdCardContainsProjects()) {
						importProjectsFromExternalStorage();
					} else {
						loadFragment();
					}
				}
			}.execute(this);
		} else {
			loadFragment();
		}
	}

	private boolean sdCardContainsProjects() {
		if (EXTERNAL_STORAGE_ROOT_DIRECTORY.isDirectory()) {
			for (File project : EXTERNAL_STORAGE_ROOT_DIRECTORY.listFiles()) {
				if (project.isDirectory()
						&& !project.getName().equals(Constants.BACKPACK_DIRECTORY.getName())
						&& !project.getName().equals("tmp")
						&& new File(project, CODE_XML_FILE_NAME).exists()) {
					return true;
				}
			}
		}
		return false;
	}

	private void loadFragment() {
		getSupportFragmentManager().beginTransaction()
				.replace(R.id.fragment_container, new MainMenuFragment(), MainMenuFragment.TAG)
				.commit();
		setShowProgressBar(false);

		Intent intent = getIntent();
		if (intent != null
				&& intent.getAction() != null
				&& intent.getAction().equals("android.intent.action.VIEW")
				&& intent.getData() != null) {
			Uri shareUri = intent.getData();

			Intent webIntent = new Intent(this, WebViewActivity.class);
			webIntent.putExtra(WebViewActivity.INTENT_PARAMETER_URL, shareUri.toString());
			startActivity(webIntent);
		}
	}

	private void importProjectsFromExternalStorage() {
		ProjectManager.getInstance().setCurrentProject(null);

		PreferenceManager.getDefaultSharedPreferences(this)
				.edit().putBoolean(SHOW_COPY_PROJECTS_FROM_EXTERNAL_STORAGE_DIALOG, false)
				.apply();

		List<File> dirs = new ArrayList<>();

		for (File dir : EXTERNAL_STORAGE_ROOT_DIRECTORY.listFiles()) {
			if (dir.getName().equals(BACKPACK_DIRECTORY.getName())) {
				try {
					if (BACKPACK_DIRECTORY.exists()) {
						StorageOperations.deleteDir(BACKPACK_DIRECTORY);
					}
					StorageOperations.copyDir(dir, BACKPACK_DIRECTORY);
				} catch (IOException e) {
					Log.e(TAG, "Cannot import backpack from external storage", e);
				}
			} else if (dir.getName().equals(TMP_DIR_NAME)) {
				try {
					StorageOperations.deleteDir(dir);
				} catch (IOException e) {
					Log.e(TAG, "Cannot delete legacy cache dir at: " + dir.getAbsolutePath(), e);
				}
			} else {
				dirs.add(dir);
			}
		}

		new AlertDialog.Builder(this)
				.setTitle(R.string.import_dialog_title)
				.setCancelable(false)
				.setMessage(R.string.import_dialog_message)
				.setPositiveButton(R.string.import_dialog_move_btn, (dialog, which) -> new ProjectImportTask()
						.setListener(projectMoveListener)
						.execute(dirs.toArray(new File[0])))
				.setNeutralButton(R.string.import_dialog_copy_btn, (dialog, which) -> new ProjectImportTask()
						.setListener(projectCopyListener)
						.execute(dirs.toArray(new File[0])))
				.show();
	}

	private void setShowProgressBar(boolean show) {
		findViewById(R.id.progress_bar).setVisibility(show ? View.VISIBLE : View.GONE);
		findViewById(R.id.fragment_container).setVisibility(show ? View.GONE : View.VISIBLE);
	}

	@Override
	public void onPause() {
		super.onPause();

		Project currentProject = ProjectManager.getInstance().getCurrentProject();

		if (currentProject != null) {
			new ProjectSaveTask(currentProject, getApplicationContext())
					.execute();

			Utils.setLastUsedProjectName(getApplicationContext(), currentProject.getName());
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.menu_main_menu, menu);

		String scratchConverter = getString(R.string.main_menu_scratch_converter);
		SpannableString scratchConverterBeta = new SpannableString(scratchConverter
				+ " "
				+ getString(R.string.beta));
		scratchConverterBeta.setSpan(
				new ForegroundColorSpan(getResources().getColor(R.color.beta_label_color)),
				scratchConverter.length(), scratchConverterBeta.length(),
				Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		menu.findItem(R.id.menu_scratch_converter).setTitle(scratchConverterBeta);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		menu.findItem(R.id.menu_login).setVisible(!Utils.isUserLoggedIn(this));
		menu.findItem(R.id.menu_logout).setVisible(Utils.isUserLoggedIn(this));
		if (!BuildConfig.FEATURE_SCRATCH_CONVERTER_ENABLED) {
			menu.removeItem(R.id.menu_scratch_converter);
		}
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_rate_app:
				if (Utils.isNetworkAvailable(this)) {
					try {
						startActivity(new Intent(Intent.ACTION_VIEW,
								Uri.parse("market://details?id=" + getPackageName())));
					} catch (ActivityNotFoundException e) {
						ToastUtil.showError(this, R.string.main_menu_play_store_not_installed);
					}
				} else {
					ToastUtil.showError(this, R.string.error_internet_connection);
				}
				break;
			case R.id.menu_terms_of_use:
				new TermsOfUseDialogFragment().show(getSupportFragmentManager(), TermsOfUseDialogFragment.TAG);
				break;
			case R.id.menu_privacy_policy:
				new PrivacyPolicyDialogFragment().show(getSupportFragmentManager(), PrivacyPolicyDialogFragment.TAG);
				break;
			case R.id.menu_about:
				new AboutDialogFragment().show(getSupportFragmentManager(), AboutDialogFragment.TAG);
				break;
			case R.id.menu_scratch_converter:
				if (Utils.isNetworkAvailable(this)) {
					startActivity(new Intent(this, ScratchConverterActivity.class));
				} else {
					ToastUtil.showError(this, R.string.error_internet_connection);
				}
				break;
			case R.id.settings:
				startActivity(new Intent(this, SettingsActivity.class));
				break;
			case R.id.menu_login:
				startActivity(new Intent(this, SignInActivity.class));
				break;
			case R.id.menu_logout:
				Utils.logoutUser(this);
				ToastUtil.showSuccess(this, R.string.logout_successful);
				break;
			default:
				return super.onOptionsItemSelected(item);
		}
		return true;
	}

	private void prepareStandaloneProject() {
		try {
			InputStream inputStream = getAssets().open(BuildConfig.START_PROJECT + ".zip");
			File projectDir = new File(DEFAULT_ROOT_DIRECTORY,
					FileMetaDataExtractor.encodeSpecialCharsForFileSystem(BuildConfig.PROJECT_NAME));
			new ZipArchiver()
					.unzip(inputStream, projectDir);
			new ProjectLoadTask(projectDir, this)
					.setListener(this)
					.execute();
		} catch (IOException e) {
			Log.e("STANDALONE", "Cannot unpack standalone project: ", e);
		}
	}

	@Override
	public void onLoadFinished(boolean success) {
		if (BuildConfig.FEATURE_APK_GENERATOR_ENABLED && success) {
			startActivityForResult(
					new Intent(this, StageActivity.class), StageActivity.REQUEST_START_STAGE);
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (BuildConfig.FEATURE_APK_GENERATOR_ENABLED) {
			if (requestCode == StageActivity.REQUEST_START_STAGE) {
				finish();
			}
		} else {
			super.onActivityResult(requestCode, resultCode, data);
		}
	}
}
