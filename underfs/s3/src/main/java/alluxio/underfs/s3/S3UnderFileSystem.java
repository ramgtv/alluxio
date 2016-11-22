/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.underfs.s3;

import alluxio.AlluxioURI;
import alluxio.Configuration;
import alluxio.Constants;
import alluxio.PropertyKey;
import alluxio.underfs.ObjectUnderFileSystem;
import alluxio.underfs.UnderFileSystem;
import alluxio.util.CommonUtils;
import alluxio.util.io.PathUtils;

import com.google.common.base.Preconditions;
import org.jets3t.service.Jets3tProperties;
import org.jets3t.service.S3Service;
import org.jets3t.service.ServiceException;
import org.jets3t.service.StorageObjectsChunk;
import org.jets3t.service.acl.AccessControlList;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.model.StorageObject;
import org.jets3t.service.security.AWSCredentials;
import org.jets3t.service.utils.Mimetypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.annotation.concurrent.ThreadSafe;

/**
 * S3 FS {@link UnderFileSystem} implementation based on the jets3t library.
 */
@ThreadSafe
public final class S3UnderFileSystem extends ObjectUnderFileSystem {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  /** Suffix for an empty file to flag it as a directory. */
  private static final String FOLDER_SUFFIX = "_$folder$";

  private static final byte[] DIR_HASH;

  /** Jets3t S3 client. */
  private final S3Service mClient;

  /** Bucket name of user's configured Alluxio bucket. */
  private final String mBucketName;

  /** The name of the account owner. */
  private final String mAccountOwner;

  /** The permission mode that the account owner has to the bucket. */
  private final short mBucketMode;

  static {
    try {
      DIR_HASH = MessageDigest.getInstance("MD5").digest(new byte[0]);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Constructs a new instance of {@link S3UnderFileSystem}.
   *
   * @param uri the {@link AlluxioURI} for this UFS
   * @return the created {@link S3UnderFileSystem} instance
   * @throws ServiceException when a connection to S3 could not be created
   */
  public static S3UnderFileSystem createInstance(AlluxioURI uri) throws ServiceException {
    String bucketName = uri.getHost();
    Preconditions.checkArgument(Configuration.containsKey(PropertyKey.S3N_ACCESS_KEY),
        "Property " + PropertyKey.S3N_ACCESS_KEY + " is required to connect to S3");
    Preconditions.checkArgument(Configuration.containsKey(PropertyKey.S3N_SECRET_KEY),
        "Property " + PropertyKey.S3N_SECRET_KEY + " is required to connect to S3");
    AWSCredentials awsCredentials = new AWSCredentials(
        Configuration.get(PropertyKey.S3N_ACCESS_KEY),
        Configuration.get(PropertyKey.S3N_SECRET_KEY));

    Jets3tProperties props = new Jets3tProperties();
    if (Configuration.containsKey(PropertyKey.UNDERFS_S3_PROXY_HOST)) {
      props.setProperty("httpclient.proxy-autodetect", "false");
      props.setProperty(
          "httpclient.proxy-host", Configuration.get(PropertyKey.UNDERFS_S3_PROXY_HOST));
      props.setProperty(
          "httpclient.proxy-port", Configuration.get(PropertyKey.UNDERFS_S3_PROXY_PORT));
    }
    if (Configuration.containsKey(PropertyKey.UNDERFS_S3_PROXY_HTTPS_ONLY)) {
      props.setProperty("s3service.https-only",
          Boolean.toString(Configuration.getBoolean(PropertyKey.UNDERFS_S3_PROXY_HTTPS_ONLY)));
    }
    if (Configuration.containsKey(PropertyKey.UNDERFS_S3_ENDPOINT)) {
      props.setProperty(
          "s3service.s3-endpoint", Configuration.get(PropertyKey.UNDERFS_S3_ENDPOINT));
      if (Configuration.getBoolean(PropertyKey.UNDERFS_S3_PROXY_HTTPS_ONLY)) {
        props.setProperty("s3service.s3-endpoint-https-port",
            Configuration.get(PropertyKey.UNDERFS_S3_ENDPOINT_HTTPS_PORT));
      } else {
        props.setProperty("s3service.s3-endpoint-http-port",
            Configuration.get(PropertyKey.UNDERFS_S3_ENDPOINT_HTTP_PORT));
      }
    }
    if (Configuration.containsKey(PropertyKey.UNDERFS_S3_DISABLE_DNS_BUCKETS)) {
      props.setProperty("s3service.disable-dns-buckets",
          Configuration.get(PropertyKey.UNDERFS_S3_DISABLE_DNS_BUCKETS));
    }
    if (Configuration.containsKey(PropertyKey.UNDERFS_S3_UPLOAD_THREADS_MAX)) {
      props.setProperty("threaded-service.max-thread-count",
          Configuration.get(PropertyKey.UNDERFS_S3_UPLOAD_THREADS_MAX));
    }
    if (Configuration.containsKey(PropertyKey.UNDERFS_S3_ADMIN_THREADS_MAX)) {
      props.setProperty("threaded-service.admin-max-thread-count",
          Configuration.get(PropertyKey.UNDERFS_S3_ADMIN_THREADS_MAX));
    }
    if (Configuration.containsKey(PropertyKey.UNDERFS_S3_THREADS_MAX)) {
      props.setProperty("httpclient.max-connections",
          Configuration.get(PropertyKey.UNDERFS_S3_THREADS_MAX));
    }
    LOG.debug("Initializing S3 underFs with properties: {}", props.getProperties());
    RestS3Service restS3Service = new RestS3Service(awsCredentials, null, null, props);

    String accountOwnerId = restS3Service.getAccountOwner().getId();
    // Gets the owner from user-defined static mapping from S3 canonical user id to Alluxio
    // user name.
    String owner = CommonUtils.getValueFromStaticMapping(
        Configuration.get(PropertyKey.UNDERFS_S3_OWNER_ID_TO_USERNAME_MAPPING),
        accountOwnerId);
    // If there is no user-defined mapping, use the display name.
    if (owner == null) {
      owner = restS3Service.getAccountOwner().getDisplayName();
    }
    String accountOwner = owner == null ? accountOwnerId : owner;

    AccessControlList acl = restS3Service.getBucketAcl(bucketName);
    short bucketMode = S3Utils.translateBucketAcl(acl, accountOwnerId);

    return new S3UnderFileSystem(uri, restS3Service, bucketName, bucketMode, accountOwner);
  }

  /**
   * Constructor for {@link S3UnderFileSystem}.
   *
   * @param uri the {@link AlluxioURI} for this UFS
   * @param s3Service Jets3t S3 client
   * @param bucketName bucket name of user's configured Alluxio bucket
   * @param bucketPrefix prefix of the bucket
   * @param bucketMode the permission mode that the account owner has to the bucket
   * @param accountOwner the name of the account owner
   */
  protected S3UnderFileSystem(AlluxioURI uri,
      S3Service s3Service,
      String bucketName,
      short bucketMode,
      String accountOwner) {
    super(uri);
    mClient = s3Service;
    mBucketName = bucketName;
    mBucketMode = bucketMode;
    mAccountOwner = accountOwner;
  }

  @Override
  public String getUnderFSType() {
    return "s3";
  }

  @Override
  public long getFileSize(String path) throws IOException {
    StorageObject details = getObjectDetails(path);
    if (details != null) {
      return details.getContentLength();
    } else {
      throw new FileNotFoundException(path);
    }
  }

  @Override
  public long getModificationTimeMs(String path) throws IOException {
    StorageObject details = getObjectDetails(path);
    if (details != null) {
      return details.getLastModifiedDate().getTime();
    } else {
      throw new FileNotFoundException(path);
    }
  }

  @Override
  public boolean isDirectory(String key) {
    // Root is always a folder
    if (isRoot(key)) {
      return true;
    }
    try {
      String keyAsFolder = convertToFolderName(stripPrefixIfPresent(key));
      mClient.getObjectDetails(mBucketName, keyAsFolder);
      // If no exception is thrown, the key exists as a folder
      return true;
    } catch (ServiceException s) {
      // It is possible that the folder has not been encoded as a _$folder$ file
      try {
        String path = PathUtils.normalizePath(stripPrefixIfPresent(key), PATH_SEPARATOR);
        // Check if anything begins with <path>/
        S3Object[] objs = mClient.listObjects(mBucketName, path, "");
        // If there are, this is a folder and we can create the necessary metadata
        if (objs.length > 0) {
          mkdirsInternal(path);
          return true;
        } else {
          return false;
        }
      } catch (ServiceException s2) {
        return false;
      }
    }
  }

  @Override
  public boolean isFile(String path) throws IOException {
    try {
      return mClient.getObjectDetails(mBucketName, stripPrefixIfPresent(path)) != null;
    } catch (ServiceException e) {
      return false;
    }
  }

  @Override
  public InputStream open(String path) throws IOException {
    try {
      path = stripPrefixIfPresent(path);
      return new S3InputStream(mBucketName, path, mClient);
    } catch (ServiceException e) {
      LOG.error("Failed to open file: {}", path, e);
      return null;
    }
  }

  /**
   * Opens a S3 object at given position and returns the opened input stream.
   *
   * @param path the S3 object path
   * @param pos the position to open at
   * @return the opened input stream
   * @throws IOException if failed to open file at position
   */
  public InputStream openAtPosition(String path, long pos) throws IOException {
    try {
      path = stripPrefixIfPresent(path);
      return new S3InputStream(mBucketName, path, mClient, pos);
    } catch (ServiceException e) {
      LOG.error("Failed to open file {} at position {}:", path, pos, e);
      return null;
    }
  }

  // Setting S3 owner via Alluxio is not supported yet. This is a no-op.
  @Override
  public void setOwner(String path, String user, String group) {}

  // Setting S3 mode via Alluxio is not supported yet. This is a no-op.
  @Override
  public void setMode(String path, short mode) throws IOException {}

  // Returns the account owner.
  @Override
  public String getOwner(String path) throws IOException {
    return mAccountOwner;
  }

  // No group in S3 ACL, returns the account owner.
  @Override
  public String getGroup(String path) throws IOException {
    return mAccountOwner;
  }

  // Returns the account owner's permission mode to the S3 bucket.
  @Override
  public short getMode(String path) throws IOException {
    return mBucketMode;
  }

  @Override
  protected boolean copy(String src, String dst) {
    src = stripPrefixIfPresent(src);
    dst = stripPrefixIfPresent(dst);
    LOG.debug("Copying {} to {}", src, dst);
    S3Object obj = new S3Object(dst);
    // Retry copy for a few times, in case some Jets3t or AWS internal errors happened during copy.
    int retries = 3;
    for (int i = 0; i < retries; i++) {
      try {
        mClient.copyObject(mBucketName, src, mBucketName, obj, false);
        return true;
      } catch (ServiceException e) {
        LOG.error("Failed to copy file {} to {}", src, dst, e);
        if (i != retries - 1) {
          LOG.error("Retrying copying file {} to {}", src, dst);
        }
      }
    }
    LOG.error("Failed to copy file {} to {}, after {} retries", src, dst, retries);
    return false;
  }

  @Override
  protected OutputStream createObject(String key) throws IOException {
    return new S3OutputStream(mBucketName, key, mClient);
  }

  @Override
  protected boolean deleteObject(String key) {
    try {
      mClient.deleteObject(mBucketName, key);
    } catch (ServiceException e) {
      LOG.error("Failed to delete {}", key, e);
      return false;
    }
    return true;
  }

  /**
   * @param key the key to get the object details of
   * @return {@link StorageObject} of the key, or null if the key does not exist
   */
  private StorageObject getObjectDetails(String key) {
    try {
      if (isDirectory(key)) {
        String keyAsFolder = convertToFolderName(stripPrefixIfPresent(key));
        return mClient.getObjectDetails(mBucketName, keyAsFolder);
      } else {
        return mClient.getObjectDetails(mBucketName, stripPrefixIfPresent(key));
      }
    } catch (ServiceException e) {
      return null;
    }
  }

  @Override
  protected String getFolderSuffix() {
    return FOLDER_SUFFIX;
  }

  @Override
  protected ObjectListingResult getObjectListing(String path, boolean recursive)
      throws IOException {
    return new S3ObjectListingResult(path, recursive);
  }

  /**
   * Wrapper over S3 {@link StorageObjectsChunk}.
   */
  final class S3ObjectListingResult implements ObjectListingResult {
    StorageObjectsChunk mChunk;
    String mDelimiter;
    boolean mDone;
    String mPath;
    String mPriorLastKey;

    public S3ObjectListingResult(String path, boolean recursive) {
      mDelimiter = recursive ? "" : PATH_SEPARATOR;
      mDone = false;
      mPath = path;
      mPriorLastKey = null;
    }

    @Override
    public String[] getObjectNames() {
      if (mChunk == null) {
        return null;
      }
      StorageObject[] objects = mChunk.getObjects();
      String[] ret = new String[objects.length];
      for (int i = 0; i < ret.length; ++i) {
        ret[i] = objects[i].getKey();
      }
      return ret;
    }

    @Override
    public String[] getCommonPrefixes() {
      if (mChunk == null) {
        return null;
      }
      return mChunk.getCommonPrefixes();
    }

    @Override
    public ObjectListingResult getNextChunk() throws IOException {
      if (mDone) {
        return null;
      }
      try {
        mChunk = mClient.listObjectsChunked(mBucketName, mPath, mDelimiter,
            LISTING_LENGTH, mPriorLastKey);
      } catch (ServiceException e) {
        LOG.error("Failed to list path {}", mPath, e);
        return null;
      }
      mDone = mChunk.isListingComplete();
      mPriorLastKey = mChunk.getPriorLastKey();
      return this;
    }
  }

  @Override
  protected String getRootKey() {
    return Constants.HEADER_S3N + mBucketName;
  }

  @Override
  protected boolean putObject(String key) {
    try {
      S3Object obj = new S3Object(key);
      obj.setDataInputStream(new ByteArrayInputStream(new byte[0]));
      obj.setContentLength(0);
      obj.setMd5Hash(DIR_HASH);
      obj.setContentType(Mimetypes.MIMETYPE_BINARY_OCTET_STREAM);
      mClient.putObject(mBucketName, obj);
      return true;
    } catch (ServiceException e) {
      LOG.error("Failed to create object: {}", key, e);
      return false;
    }
  }
}
