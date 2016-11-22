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

package alluxio.underfs.s3a;

import alluxio.AlluxioURI;
import alluxio.Configuration;
import alluxio.Constants;
import alluxio.PropertyKey;
import alluxio.underfs.ObjectUnderFileSystem;
import alluxio.underfs.UnderFileSystem;
import alluxio.util.CommonUtils;
import alluxio.util.io.PathUtils;

import com.amazonaws.AmazonClientException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.SDKGlobalConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSCredentialsProviderChain;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.S3ClientOptions;
import com.amazonaws.services.s3.internal.Mimetypes;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerConfiguration;
import com.amazonaws.util.Base64;
import com.google.common.base.Preconditions;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import javax.annotation.concurrent.ThreadSafe;

/**
 * S3 {@link UnderFileSystem} implementation based on the aws-java-sdk-s3 library.
 */
@ThreadSafe
public class S3AUnderFileSystem extends ObjectUnderFileSystem {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  /** Suffix for an empty file to flag it as a directory. */
  private static final String FOLDER_SUFFIX = "_$folder$";

  /** Static hash for a directory's empty contents. */
  private static final String DIR_HASH;

  /** Threshold to do multipart copy. */
  private static final long MULTIPART_COPY_THRESHOLD = 100 * Constants.MB;

  /** AWS-SDK S3 client. */
  private final AmazonS3Client mClient;

  /** Bucket name of user's configured Alluxio bucket. */
  private final String mBucketName;

  /** Transfer Manager for efficient I/O to S3. */
  private final TransferManager mManager;

  /** The name of the account owner. */
  private final String mAccountOwner;

  /** The permission mode that the account owner has to the bucket. */
  private final short mBucketMode;

  static {
    byte[] dirByteHash = DigestUtils.md5(new byte[0]);
    DIR_HASH = new String(Base64.encode(dirByteHash));
  }

  /**
   * Constructs a new instance of {@link S3AUnderFileSystem}.
   *
   * @param uri the {@link AlluxioURI} for this UFS
   * @return the created {@link S3AUnderFileSystem} instance
   */
  public static S3AUnderFileSystem createInstance(AlluxioURI uri) {

    String bucketName = uri.getHost();

    // Set the aws credential system properties based on Alluxio properties, if they are set
    if (Configuration.containsKey(PropertyKey.S3A_ACCESS_KEY)) {
      System.setProperty(SDKGlobalConfiguration.ACCESS_KEY_SYSTEM_PROPERTY,
          Configuration.get(PropertyKey.S3A_ACCESS_KEY));
    }
    if (Configuration.containsKey(PropertyKey.S3A_SECRET_KEY)) {
      System.setProperty(SDKGlobalConfiguration.SECRET_KEY_SYSTEM_PROPERTY,
          Configuration.get(PropertyKey.S3A_SECRET_KEY));
    }

    // Checks, in order, env variables, system properties, profile file, and instance profile
    AWSCredentialsProvider credentials =
        new AWSCredentialsProviderChain(new DefaultAWSCredentialsProviderChain());

    // Set the client configuration based on Alluxio configuration values
    ClientConfiguration clientConf = new ClientConfiguration();

    // Socket timeout
    clientConf.setSocketTimeout(Configuration.getInt(PropertyKey.UNDERFS_S3A_SOCKET_TIMEOUT_MS));

    // HTTP protocol
    if (Configuration.getBoolean(PropertyKey.UNDERFS_S3A_SECURE_HTTP_ENABLED)) {
      clientConf.setProtocol(Protocol.HTTPS);
    } else {
      clientConf.setProtocol(Protocol.HTTP);
    }

    // Proxy host
    if (Configuration.containsKey(PropertyKey.UNDERFS_S3_PROXY_HOST)) {
      clientConf.setProxyHost(Configuration.get(PropertyKey.UNDERFS_S3_PROXY_HOST));
    }

    // Proxy port
    if (Configuration.containsKey(PropertyKey.UNDERFS_S3_PROXY_PORT)) {
      clientConf.setProxyPort(Configuration.getInt(PropertyKey.UNDERFS_S3_PROXY_PORT));
    }

    AmazonS3Client amazonS3Client = new AmazonS3Client(credentials, clientConf);
    // Set a custom endpoint.
    if (Configuration.containsKey(PropertyKey.UNDERFS_S3_ENDPOINT)) {
      amazonS3Client.setEndpoint(Configuration.get(PropertyKey.UNDERFS_S3_ENDPOINT));
    }
    // Disable DNS style buckets, this enables path style requests.
    if (Configuration.getBoolean(PropertyKey.UNDERFS_S3_DISABLE_DNS_BUCKETS)) {
      S3ClientOptions clientOptions = S3ClientOptions.builder().setPathStyleAccess(true).build();
      amazonS3Client.setS3ClientOptions(clientOptions);
    }

    TransferManager transferManager = new TransferManager(amazonS3Client);

    TransferManagerConfiguration transferConf = new TransferManagerConfiguration();
    transferConf.setMultipartCopyThreshold(MULTIPART_COPY_THRESHOLD);
    transferManager.setConfiguration(transferConf);

     // Default to readable and writable by the user.
    short bucketMode = (short) 700;
    String accountOwner = ""; // There is no known account owner by default.
    // if ACL enabled inherit bucket acl for all the objects.
    if (Configuration.getBoolean(PropertyKey.UNDERFS_S3A_INHERIT_ACL)) {
      String accountOwnerId = amazonS3Client.getS3AccountOwner().getId();
      // Gets the owner from user-defined static mapping from S3 canonical user
      // id to Alluxio user name.
      String owner = CommonUtils.getValueFromStaticMapping(
          Configuration.get(PropertyKey.UNDERFS_S3_OWNER_ID_TO_USERNAME_MAPPING),
          accountOwnerId);
      // If there is no user-defined mapping, use the display name.
      if (owner == null) {
        owner = amazonS3Client.getS3AccountOwner().getDisplayName();
      }
      accountOwner = owner == null ? accountOwnerId : owner;

      AccessControlList acl = amazonS3Client.getBucketAcl(bucketName);
      bucketMode = S3AUtils.translateBucketAcl(acl, accountOwnerId);
    }
    return new S3AUnderFileSystem(uri, amazonS3Client, bucketName, bucketMode, accountOwner,
        transferManager);
  }

  /**
   * Constructor for {@link S3AUnderFileSystem}.
   *
   * @param uri the {@link AlluxioURI} for this UFS
   * @param amazonS3Client AWS-SDK S3 client
   * @param bucketName bucket name of user's configured Alluxio bucket
   * @param bucketPrefix prefix of the bucket
   * @param bucketMode the permission mode that the account owner has to the bucket
   * @param accountOwner the name of the account owner
   * @param transferManager Transfer Manager for efficient I/O to S3
   */
  protected S3AUnderFileSystem(AlluxioURI uri,
      AmazonS3Client amazonS3Client,
      String bucketName,
      short bucketMode,
      String accountOwner,
      TransferManager transferManager) {
    super(uri);
    mClient = amazonS3Client;
    mBucketName = bucketName;
    mBucketMode = bucketMode;
    mAccountOwner = accountOwner;
    mManager = transferManager;
  }

  @Override
  public String getUnderFSType() {
    return "s3";
  }

  @Override
  public long getFileSize(String path) throws IOException {
    try {
      ObjectMetadata details = mClient.getObjectMetadata(mBucketName, stripPrefixIfPresent(path));
      return details.getContentLength();
    } catch (AmazonClientException e) {
      LOG.error("Error fetching file size, assuming file does not exist", e);
      throw new FileNotFoundException(path);
    }
  }

  @Override
  public long getModificationTimeMs(String path) throws IOException {
    ObjectMetadata details = getObjectDetails(path);
    if (details != null) {
      return details.getLastModified().getTime();
    } else {
      throw new FileNotFoundException(path);
    }
  }

  @Override
  public boolean isDirectory(String key) throws IOException {
    // Root is always a folder
    return isRoot(key) || getFolderMetadata(key) != null;
  }

  @Override
  public boolean isFile(String path) throws IOException {
    // Directly try to get the file metadata, if we fail it either is a folder or does not exist
    try {
      mClient.getObjectMetadata(mBucketName, stripPrefixIfPresent(path));
      return true;
    } catch (AmazonClientException e) {
      return false;
    }
  }

  @Override
  public InputStream open(String path) throws IOException {
    try {
      path = stripPrefixIfPresent(path);
      return new S3AInputStream(mBucketName, path, mClient);
    } catch (AmazonClientException e) {
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
   * @throws java.io.IOException if failed to open file at position
   */
  public InputStream openAtPosition(String path, long pos) throws IOException {
    try {
      path = stripPrefixIfPresent(path);
      return new S3AInputStream(mBucketName, path, mClient, pos);
    } catch (AmazonClientException e) {
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
    // Retry copy for a few times, in case some AWS internal errors happened during copy.
    int retries = 3;
    for (int i = 0; i < retries; i++) {
      try {
        CopyObjectRequest request = new CopyObjectRequest(mBucketName, src, mBucketName, dst);
        if (Configuration.getBoolean(PropertyKey.UNDERFS_S3A_SERVER_SIDE_ENCRYPTION_ENABLED)) {
          ObjectMetadata meta = new ObjectMetadata();
          meta.setSSEAlgorithm(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
          request.setNewObjectMetadata(meta);
        }
        mManager.copy(request).waitForCopyResult();
        return true;
      } catch (AmazonClientException | InterruptedException e) {
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
    return new S3AOutputStream(mBucketName, key, mManager);
  }

  @Override
  protected boolean deleteObject(String key) throws IOException {
    try {
      mClient.deleteObject(mBucketName, key);
    } catch (AmazonClientException e) {
      LOG.error("Failed to delete {}", key, e);
      return false;
    }
    return true;
  }

  /**
   * Gets the metadata associated with a non-root key if it represents a folder. This method will
   * return null if the key is not a folder. If the key exists as a prefix but does not have the
   * folder dummy file, a folder dummy file will be created.
   *
   * @param key the key to get the metadata for
   * @return the metadata of the folder, or null if the key does not represent a folder
   */
  private ObjectMetadata getFolderMetadata(String key) {
    Preconditions.checkArgument(!isRoot(key));
    String keyAsFolder = convertToFolderName(stripPrefixIfPresent(key));
    ObjectMetadata meta = null;
    try {
      meta = mClient.getObjectMetadata(mBucketName, keyAsFolder);
      // If no exception is thrown, the key exists as a folder
    } catch (AmazonClientException e) {
      // It is possible that the folder has not been encoded as a _$folder$ file
      try {
        String dir = stripPrefixIfPresent(key);
        String dirPrefix = PathUtils.normalizePath(dir, PATH_SEPARATOR);
        // Check if anything begins with <folder_path>/
        ObjectListing objs = mClient.listObjects(mBucketName, dirPrefix);
        // If there are, this is a folder and we can create the necessary metadata
        if (objs.getObjectSummaries().size() > 0) {
          mkdirsInternal(dir);
          meta = mClient.getObjectMetadata(mBucketName, keyAsFolder);
        }
      } catch (AmazonClientException ace) {
        return null;
      }
    }
    return meta;
  }

  @Override
  protected String getFolderSuffix() {
    return FOLDER_SUFFIX;
  }

  /**
   * @param key the key to get the object details of
   * @return {@link ObjectMetadata} of the key, or null if the key does not exist
   */
  private ObjectMetadata getObjectDetails(String key) {
    // We try to get the metadata as a file and then a folder without checking isFolder to reduce
    // the number of calls to S3.
    try {
      return mClient.getObjectMetadata(mBucketName, stripPrefixIfPresent(key));
    } catch (AmazonClientException e) {
      // Its possible that the object is not a file but a folder
      return getFolderMetadata(stripPrefixIfPresent(key));
    }
  }

  @Override
  protected ObjectListingResult getObjectListing(String path, boolean recursive)
      throws IOException {
    return new S3ObjectListingResult(path, recursive);
  }

  /**
   * Wrapper over S3 {@link ListObjectsV2Request}.
   */
  final class S3ObjectListingResult implements ObjectListingResult {
    String mPath;
    ListObjectsV2Request mRequest;
    ListObjectsV2Result mResult;

    public S3ObjectListingResult(String path, boolean recursive) {
      String delimiter = recursive ? "" : PATH_SEPARATOR;
      mPath = path;
      mRequest =
          new ListObjectsV2Request().withBucketName(mBucketName).withPrefix(path)
              .withDelimiter(delimiter).withMaxKeys(LISTING_LENGTH);
    }

    @Override
    public String[] getObjectNames() {
      if (mResult == null) {
        return null;
      }
      List<S3ObjectSummary> objects = mResult.getObjectSummaries();
      String[] ret = new String[objects.size()];
      int i = 0;
      for (S3ObjectSummary obj : objects) {
        ret[i] = obj.getKey();
      }
      return ret;
    }

    @Override
    public String[] getCommonPrefixes() {
      if (mResult == null) {
        return null;
      }
      List<String> res = mResult.getCommonPrefixes();
      return res.toArray(new String[res.size()]);
    }

    @Override
    public ObjectListingResult getNextChunk() throws IOException {
      if (mResult != null && !mResult.isTruncated()) {
        return null;
      }
      try {
        // Query S3 for the next batch of objects
        mResult = mClient.listObjectsV2(mRequest);
        // Advance the request continuation token to the next set of objects
        mRequest.setContinuationToken(mResult.getNextContinuationToken());
      } catch (AmazonClientException e) {
        LOG.error("Failed to list path {}", mPath, e);
        return null;
      }
      return this;
    }
  }

  @Override
  protected String getRootKey() {
    return Constants.HEADER_S3A + mBucketName;
  }

  @Override
  protected boolean putObject(String key) {
    try {
      ObjectMetadata meta = new ObjectMetadata();
      meta.setContentLength(0);
      meta.setContentMD5(DIR_HASH);
      meta.setContentType(Mimetypes.MIMETYPE_OCTET_STREAM);
      mClient.putObject(new PutObjectRequest(mBucketName, key, new ByteArrayInputStream(
          new byte[0]), meta));
      return true;
    } catch (AmazonClientException e) {
      LOG.error("Failed to create object: {}", key, e);
      return false;
    }
  }
}
