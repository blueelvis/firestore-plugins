package io.cdap.plugin.gcp.firestore.common;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.ServiceOptions;
import com.google.common.base.Strings;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Macro;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.plugin.PluginConfig;
import io.cdap.cdap.etl.api.FailureCollector;
import io.cdap.plugin.common.Constants;
import io.cdap.plugin.common.IdUtils;

import java.io.IOException;
import javax.annotation.Nullable;

/**
 * Contains config properties common to all GCP plugins, like project id and service account key.
 */
public class FirestoreConfig extends PluginConfig {
  public static final String NAME_PROJECT = "project";
  public static final String NAME_SERVICE_ACCOUNT_TYPE = "serviceAccountType";
  public static final String NAME_DATABASE = "databaseName";
  public static final String NAME_SERVICE_ACCOUNT_FILE_PATH = "serviceFilePath";
  public static final String NAME_SERVICE_ACCOUNT_JSON = "serviceAccountJSON";
  public static final String AUTO_DETECT = "auto-detect";
  public static final String SERVICE_ACCOUNT_FILE_PATH = "filePath";
  public static final String SERVICE_ACCOUNT_JSON = "JSON";

  @Name(Constants.Reference.REFERENCE_NAME)
  @Description("Uniquely identifies this plugin for lineage, annotating metadata, etc.")
  protected String referenceName;

  @Name(NAME_PROJECT)
  @Description("Google Cloud Project ID, which uniquely identifies a project. "
    + "It can be found on the Dashboard in the Google Cloud Platform Console.")
  @Macro
  @Nullable
  protected String project;

  @Name(NAME_DATABASE)
  @Description("Name of the Firestore Database. "
    + "If not specified, it will use '(default)'.")
  @Macro
  protected String databaseName;

  @Name(NAME_SERVICE_ACCOUNT_TYPE)
  @Description("Service account type, file path where the service account is located or the JSON content of the " +
    "service account.")
  protected String serviceAccountType;

  @Name(NAME_SERVICE_ACCOUNT_FILE_PATH)
  @Description("Path on the local file system of the service account key used "
    + "for authorization. Can be set to 'auto-detect' when running on a Dataproc cluster. "
    + "When running on other clusters, the file must be present on every node in the cluster.")
  @Nullable
  protected String serviceFilePath;

  @Name(NAME_SERVICE_ACCOUNT_JSON)
  @Description("Content of the service account file.")
  @Nullable
  protected String serviceAccountJson;

  @Name(SERVICE_ACCOUNT_FILE_PATH)
  @Description("File path of the Service Account JSON file.")
  @Nullable
  protected String serviceAccountFilePath;

  @Name(SERVICE_ACCOUNT_JSON)
  @Description("Service Account JSON")
  @Nullable
  protected String serviceAccountJsonContent;

  public String getProject() {
    String projectId = tryGetProject();
    if (projectId == null) {
      throw new IllegalArgumentException(
        "Could not detect Google Cloud project id from the environment. Please specify a project id.");
    }
    return projectId;
  }

  @Nullable
  public String tryGetProject() {
    if (containsMacro(NAME_PROJECT) && Strings.isNullOrEmpty(project)) {
      return null;
    }
    String projectId = project;
    if (Strings.isNullOrEmpty(project) || AUTO_DETECT.equals(project)) {
      projectId = ServiceOptions.getDefaultProjectId();
    }
    return projectId;
  }

  @Nullable
  public String getServiceAccountFilePath() {
    if (containsMacro(NAME_SERVICE_ACCOUNT_FILE_PATH) || serviceFilePath == null ||
      serviceFilePath.isEmpty() || AUTO_DETECT.equals(serviceFilePath)) {
      return null;
    }
    return serviceFilePath;
  }

  @Nullable
  public String getServiceAccountJson() {
    if (containsMacro(NAME_SERVICE_ACCOUNT_JSON) || Strings.isNullOrEmpty(serviceAccountJson)) {
      return null;
    }
    return serviceAccountJson;
  }

  public String getServiceAccountType() {
    if (containsMacro(NAME_SERVICE_ACCOUNT_TYPE) || Strings.isNullOrEmpty(serviceAccountType)) {
      return null;
    }
    return serviceAccountType;
  }

  public Boolean isServiceAccountJson() {
    String serviceAccountType = getServiceAccountType();
    return Strings.isNullOrEmpty(serviceAccountType) ? null : serviceAccountType.equals(SERVICE_ACCOUNT_JSON);
  }

  public Boolean isServiceAccountFilePath() {
    String serviceAccountType = getServiceAccountType();
    return Strings.isNullOrEmpty(serviceAccountType) ? null : serviceAccountType.equals(SERVICE_ACCOUNT_FILE_PATH);
  }

  @Nullable
  public String getServiceAccount() {
    Boolean serviceAccountJson = isServiceAccountJson();
    if (serviceAccountJson == null) {
      return null;
    }
    return serviceAccountJson ? getServiceAccountJson() : getServiceAccountFilePath();
  }

  /**
   * Validates the given referenceName to consists of characters allowed to represent a dataset.
   */
  public void validate(FailureCollector collector) {
    IdUtils.validateReferenceName(referenceName, collector);
  }

  public String getDatabaseName() {
    if (containsMacro(NAME_DATABASE) && Strings.isNullOrEmpty(databaseName)) {
      return "(default)";
    }
    return databaseName;
  }

  public String getReferenceName() {
    return referenceName;
  }

  /**
   * Return true if the service account is set to auto-detect but it can't be fetched from the environment.
   * This shouldn't result in a deployment failure, as the credential could be detected at runtime if the pipeline
   * runs on dataproc. This should primarily be used to check whether certain validation logic should be skipped.
   *
   * @return true if the service account is set to auto-detect but it can't be fetched from the environment.
   */
  public boolean autoServiceAccountUnavailable() {
    if (getServiceAccountFilePath() == null && SERVICE_ACCOUNT_FILE_PATH.equals(getServiceAccountType())) {
      try {
        ServiceAccountCredentials.getApplicationDefault();
      } catch (IOException e) {
        return true;
      }
    }
    return false;
  }
}
