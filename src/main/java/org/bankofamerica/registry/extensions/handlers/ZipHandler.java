package org.bankofamerica.registry.extensions.handlers;

import org.apache.axiom.om.OMElement;
import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.Documentation;
import org.wso2.carbon.apimgt.api.model.DocumentationType;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerFactory;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.RegistryConstants;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.jdbc.handlers.Handler;
import org.wso2.carbon.registry.core.jdbc.handlers.RequestContext;
import org.wso2.carbon.registry.core.jdbc.utils.Transaction;
import org.wso2.carbon.registry.core.session.CurrentSession;
import org.wso2.carbon.registry.core.session.UserRegistry;
import org.wso2.carbon.registry.extensions.handlers.utils.WSDLProcessor;
import org.wso2.carbon.registry.extensions.utils.CommonUtil;
import org.wso2.carbon.user.core.UserRealm;

import javax.xml.namespace.QName;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@SuppressWarnings({"unused"})
public class ZipHandler extends Handler {

//    <handler class="org.wso2.carbon.registry.extensions.handlers.ZipHandler">
//        <property name="wsdlMediaType">application/wsdl+xml</property>
//        <property name="schemaMediaType">application/xsd+xml</property>
//        <property name="threadPoolSize">50</property>
//        <property name="useOriginalSchema">true</property>
//        <!--property name="disableWSDLValidation">true</property>
//        <property name="disableSchemaValidation">true</property>
//        <property name="wsdlExtension">.wsdl</property>
//        <property name="schemaExtension">.xsd</property>
//        <property name="archiveExtension">.gar</property>
//        <property name="tempFilePrefix">wsdl</property-->
//        <property name="schemaLocationConfiguration" type="xml">
//            <location>/governance/schemas/</location>
//        </property>
//        <property name="wsdlLocationConfiguration" type="xml">
//            <location>/governance/wsdls/</location>
//        </property>
//        <filter class="org.wso2.carbon.registry.core.jdbc.handlers.filters.MediaTypeMatcher">
//            <property name="mediaType">application/vnd.wso2.governance-archive</property>
//        </filter>
//    </handler>

    private String wsdlMediaType = "application/wsdl-xml";

    private String wsdlExtension = ".wsdl";

    private String tempFilePrefix = "wsdl";
    private String wsdlLocation;
    private boolean disableWSDLValidation = false;
    private boolean disableSchemaValidation = false;
    private boolean useOriginalSchema = false;
    private OMElement wsdlLocationConfiguration;
    private boolean disableSymlinkCreation = true;
    private static int numberOfRetry = 5;
    private boolean disableWADLValidation = false;
    private String archiveExtension = ".gar";
    protected String locationTag = "location";
    private boolean createService = true;
    private String docPath;
    private Resource zipResource;
    private String apiName;
    private String apiVersion;
    APIIdentifier apiId;
    private String apiProvider;
    private String apiPath;
    public void setNumberOfRetry(String numberOfRetry) {
        ZipHandler.numberOfRetry = Integer.parseInt(numberOfRetry);
    }
    APIProvider apiProviderOb;

    public boolean isDisableSymlinkCreation() {
        return disableSymlinkCreation;
    }

    public void setDisableSymlinkCreation(String disableSymlinkCreation) {
        this.disableSymlinkCreation = Boolean.toString(true).equals(disableSymlinkCreation);
    }


    private int threadPoolSize = 50;

    private static final Log log = LogFactory.getLog(ZipHandler.class);

    public void setThreadPoolSize(String threadPoolSize) {
        this.threadPoolSize = Integer.parseInt(threadPoolSize);
    }

    public OMElement getWsdlLocationConfiguration() {
        return wsdlLocationConfiguration;
    }

    public void setWsdlLocationConfiguration(OMElement locationConfiguration) throws RegistryException {
        Iterator confElements = locationConfiguration.getChildElements();
        while (confElements.hasNext()) {
            OMElement confElement = (OMElement)confElements.next();
            if (confElement.getQName().equals(new QName(locationTag))) {
                wsdlLocation = confElement.getText();
                if (!wsdlLocation.startsWith(RegistryConstants.PATH_SEPARATOR)) {
                    wsdlLocation = RegistryConstants.PATH_SEPARATOR + wsdlLocation;
                }
                if (!wsdlLocation.endsWith(RegistryConstants.PATH_SEPARATOR)) {
                    wsdlLocation = wsdlLocation + RegistryConstants.PATH_SEPARATOR;
                }
            }
        }
        WSDLProcessor.setCommonWSDLLocation(wsdlLocation);
        this.wsdlLocationConfiguration = locationConfiguration;
    }

    public void put(RequestContext requestContext) throws RegistryException {
        System.out.println("ZipHandler called !!!!");
        if (!CommonUtil.isUpdateLockAvailable()) {
            return;
        }
        CommonUtil.acquireUpdateLock();
        try {
            zipResource = requestContext.getResource();
            String path = requestContext.getResourcePath().getPath();
            try {
                // If the document is already there, we don't need to re-run this handler unless the content is changed.
                // Re-running this handler causes issues with downstream handlers and other behaviour (ex:- lifecycles).
                // If you need to do a replace programatically, delete-then-replace.
                if (requestContext.getRegistry().resourceExists(path)) {
                    // TODO: Add logic to compare content, and return only if the content didn't change.
                    return;
                } else {
                    requestContext.getRegistry().put(requestContext.getResourcePath().getPath(), zipResource);
                    String temp = path.substring(0, path.lastIndexOf("/"));
                    docPath= temp.substring(0,temp.lastIndexOf("/"));
                    String[] pathContents = temp.split("/");
                    apiVersion = pathContents[pathContents.length-3];
                    apiName = pathContents[pathContents.length-4];
                    apiProvider =  pathContents[pathContents.length-5];
                    apiId = new APIIdentifier(apiProvider, apiName, apiName);
                    apiPath = APIUtil.getAPIPath(apiId);
                    apiProviderOb = APIManagerFactory.getInstance().getAPIProvider(apiProvider);
                }
            } catch (Exception ignore) { }
            try {
                if (zipResource != null) {
                    Object resourceContent = zipResource.getContent();
                    InputStream in = new ByteArrayInputStream((byte[]) resourceContent);
                    Stack<File> fileList = new Stack<File>();
                    List<String> uriList = new LinkedList<String>();
                    List<UploadTask> tasks = new LinkedList<UploadTask>();

                    int threadPoolSize = this.threadPoolSize;
                    System.out.println("ZipHandler called at LINE 222!!!!");
                    File tempFile = File.createTempFile(tempFilePrefix, archiveExtension);
                    File tempDir = new File(tempFile.getAbsolutePath().substring(0,
                            tempFile.getAbsolutePath().length() - archiveExtension.length()));
                    try {
                        BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(tempFile));
                        try {
                            byte[] contentChunk = new byte[1024];
                            int byteCount;
                            while ((byteCount = in.read(contentChunk)) != -1) {
                                out.write(contentChunk, 0, byteCount);
                            }
                            out.flush();
                        } finally {
                            out.close();
                        }
                        ZipEntry entry;

                        makeDir(tempDir);
                        ZipInputStream zs;

                        List<String> wsdlUriList = new LinkedList<String>();

                        zs = new ZipInputStream(new FileInputStream(tempFile));
                        System.out.println("ZipHandler called at LINE 246!!!!");
                        try {
                            entry = zs.getNextEntry();
                            while (entry != null) {
                                String entryName = entry.getName();
                                FileOutputStream os;
                                File file = new File(tempFile.getAbsolutePath().substring(0,
                                        tempFile.getAbsolutePath().length() -
                                                archiveExtension.length()) + File.separator + entryName);
                                if (entry.isDirectory()) {
                                    if (!file.exists()) {
                                        makeDirs(file);
                                        fileList.push(file);
                                    }
                                    entry = zs.getNextEntry();
                                    continue;
                                }
                                File parentFile = file.getParentFile();
                                if (!parentFile.exists()) {
                                    makeDirs(parentFile);
                                }
                                System.out.println("ZipHandler called at LINE 267!!!!");
                                os = new FileOutputStream(file);
                                try {
                                    fileList.push(file);
                                    byte[] contentChunk = new byte[1024];
                                    int byteCount;
                                    while ((byteCount = zs.read(contentChunk)) != -1) {
                                        os.write(contentChunk, 0, byteCount);
                                    }
                                } finally {
                                    os.close();
                                }
                                zs.closeEntry();
                                entry = zs.getNextEntry();
                                if (entryName != null &&
                                        entryName.toLowerCase().endsWith(wsdlExtension)) {
                                    updateList(tempFile,wsdlUriList,entryName);
                                }
                            }
                            System.out.println("WSDL URI List :"+wsdlUriList);
                        } finally {
                            zs.close();
                        }
                        Map<String, String> localPathMap = null;
                        if (CurrentSession.getLocalPathMap() != null) {
                            localPathMap =
                                    Collections.unmodifiableMap(CurrentSession.getLocalPathMap());
                        }
                        for (String uri : wsdlUriList) {
                            tasks.add(new UploadWSDLTask(requestContext, uri,
                                    CurrentSession.getTenantId(),
                                    CurrentSession.getUserRegistry(), CurrentSession.getUserRealm(),
                                    CurrentSession.getUser(), CurrentSession.getCallerTenantId(),
                                    localPathMap));
                        }
                        if (wsdlUriList.isEmpty()) {
                            throw new RegistryException(
                                    "No Files found in the given archive");
                        }
                       /* tasks.add(new UploadFileTask(requestContext, requestContext.getResourcePath().getPath(),
                                CurrentSession.getTenantId(),
                                CurrentSession.getUserRegistry(), CurrentSession.getUserRealm(),
                                CurrentSession.getUser(), CurrentSession.getCallerTenantId(),
                                localPathMap, zipResource.getMediaType()));*/

                        // calculate thread pool size for efficient use of resources in concurrent
                        // update scenarios.
                        int toAdd = wsdlUriList.size();
                        if (toAdd < threadPoolSize) {
                            if (toAdd < (threadPoolSize / 8)) {
                                threadPoolSize = 0;
                            } else if (toAdd < (threadPoolSize / 2)) {
                                threadPoolSize = (threadPoolSize / 8);
                            } else {
                                threadPoolSize = (threadPoolSize / 4);
                            }
                        }
                    } finally {
                        in.close();
                        resourceContent = null;
                        zipResource.setContent(null);
                    }
                    uploadFiles(tasks, tempFile, fileList, tempDir, threadPoolSize, path, uriList,
                            requestContext);
                }
            } catch (IOException e) {
                throw new RegistryException("Error occurred while unpacking Governance Archive", e);
            }
            if (Transaction.isRollbacked()) {
                throw new RegistryException("A nested transaction was rollbacked and therefore " +
                        "cannot proceed with this action.");
            }
            requestContext.setProcessingComplete(true);
        } finally {
            CommonUtil.releaseUpdateLock();
        }
    }

    /*
     * 
     */
    private void updateList(File tempFile,List<String> refUriList,String entryName ){
        System.out.println("ZipHandler called at LINE 283!!!!");
        String uri = tempFile.toURI().toString();
        uri = uri.substring(0, uri.length() -
                archiveExtension.length()) + "/" + entryName;
        if (uri.startsWith("file:")) {
            uri = uri.substring(5);
        }
        System.out.println("ZipHandler called at LINE 288!!!!");
        while (uri.startsWith("/")) {
            uri = uri.substring(1);
        }
        if (uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() -1);
        }
        uri = "/"+uri;
        refUriList.add(uri);

    }

    /**
     * Method that runs the WSDL upload procedure.
     *
     * @param requestContext the request context for the import/put operation
     * @param uri the URL from which the WSDL is imported
     *
     * @return the path at which the WSDL was uploaded to
     *
     * @throws RegistryException if the operation failed.
     */
    protected String addWSDLFromZip(RequestContext requestContext, String uri)
            throws RegistryException {
        if (uri != null) {
            System.out.println("LINE 449: adding WSDL..");
            String content = "";
            File file = new File(uri);
            try {
                int len;
                char[] chr = new char[4096];
                final StringBuffer buffer = new StringBuffer();
                final FileReader reader = new FileReader(file);
                try {
                    while ((len = reader.read(chr)) > 0) {
                        buffer.append(chr, 0, len);
                    }
                } finally {
                    reader.close();
                }
                content=buffer.toString();
                Registry registry=requestContext.getRegistry();
                Resource local = registry.newResource();
                String path = requestContext.getResourcePath().getPath();
                String wsdlNameWithoutForwardSlash = null;
                if (path.lastIndexOf("/") != -1) {
                    path = path.substring(0, path.lastIndexOf("/"));
                } else {
                    path = "";
                }
                String wsdlName = uri;
                if (wsdlName.lastIndexOf("/") != -1) {
                    wsdlName = wsdlName.substring(wsdlName.lastIndexOf("/"));
                    wsdlNameWithoutForwardSlash = wsdlName.substring(wsdlName.lastIndexOf("/")+1);
                } else {
                    wsdlName = "/" + wsdlName;
                }
                path = path + wsdlName;
                local.setMediaType(wsdlMediaType);
                local.setContent(content);
                registry.put(path, local);
                addDocument(wsdlNameWithoutForwardSlash, path);
                System.out.println("LINE 449: adding WSDL.. PATH="+path);
            } catch (IOException e) {
                System.out.println("Error file reading content of zip file");
            }
        }
        return "successfully added wsdl file to the registry";
    }

    /**
     * Method to customize the WSDL Processor.
     * @param requestContext the request context for the import/put operation
     * @return the WSDL Processor instance.
     */
    @SuppressWarnings("unused")
    protected WSDLProcessor buildWSDLProcessor(RequestContext requestContext) {
        WSDLProcessor wsdlProcessor = new WSDLProcessor(requestContext);
        wsdlProcessor.setCreateService(getCreateService());
        return wsdlProcessor;
    }

    /**
     * Method to customize the WSDL Processor.
     * @param requestContext the request context for the import/put operation
     * @param useOriginalSchema whether the schema to be original
     * @return the WSDL Processor instance.
     */
    @SuppressWarnings("unused")
    protected WSDLProcessor buildWSDLProcessor(RequestContext requestContext, boolean useOriginalSchema) {
        WSDLProcessor wsdlProcessor = new WSDLProcessor(requestContext, useOriginalSchema);
        wsdlProcessor.setCreateService(getCreateService());
        return wsdlProcessor;
    }

    public boolean getCreateService() {
        return createService;
    }

    public void setCreateService(String createService) {
        this.createService = Boolean.valueOf(createService);
    }

    public void importResource(RequestContext context) {
        // We don't support importing .gar files. This is meant only for uploading WSDL files
        // and imports from the local filesystem.
        log.warn("The imported Governance Web Archive will not be extracted. To extract the content"
                + " upload the archive from the file system.");
    }

    public void setWsdlMediaType(String wsdlMediaType) {
        this.wsdlMediaType = wsdlMediaType;
    }

    public void setWsdlExtension(String wsdlExtension) {
        this.wsdlExtension = wsdlExtension;
    }

    public void setTempFilePrefix(String tempFilePrefix) {
        this.tempFilePrefix = tempFilePrefix;
    }

    public void setDisableWSDLValidation(String disableWSDLValidation) {
        this.disableWSDLValidation = Boolean.toString(true).equals(disableWSDLValidation);
    }

    public void setDisableSchemaValidation(String disableSchemaValidation) {
        this.disableSchemaValidation = Boolean.toString(true).equals(disableSchemaValidation);
    }

    public void setDisableWADLValidation(String disableWADLValidation) {
        this.disableWADLValidation = Boolean.getBoolean(disableWADLValidation);
    }

    public void setUseOriginalSchema(String useOriginalSchema) {
        this.useOriginalSchema = Boolean.toString(true).equals(useOriginalSchema);
    }

    /**
     * {@inheritDoc}
     */
    protected void onPutCompleted(String path, Map<String, String> addedResources,
                                  List<String> otherResources, RequestContext requestContext)
    //Final result printing in console.
            throws RegistryException {
        log.info("Total Number of Files Uploaded: " + addedResources.size());
        List<String> failures = new LinkedList<String>();
        for (Map.Entry<String, String> e : addedResources.entrySet()) {
            if (e.getValue() == null) {
                failures.add(e.getKey());
                log.info("Failure " + failures.size() + ": " + e.getKey());
            }
        }
        log.info("Total Number of Files Failed to Upload: " + failures.size());
        if (otherResources.size() > 0) {
            log.info("Total Number of Files Not-Uploaded: " + otherResources.size());
        }
    }

    protected void uploadFiles(List<UploadTask> tasks,
                               File tempFile, Stack<File> fileList, File tempDir, int poolSize,
                               String path, List<String> uriList, RequestContext requestContext)
            throws RegistryException {
        CommonUtil.loadImportedArtifactMap();
        try {
            if (poolSize <= 0) {
                boolean updateLockAvailable = CommonUtil.isUpdateLockAvailable();
                if (!updateLockAvailable) {
                    CommonUtil.releaseUpdateLock();
                }
                try {
                    for (UploadTask task : tasks) {
                        task.run();
                    }
                } finally {
                    if (!updateLockAvailable) {
                        CommonUtil.acquireUpdateLock();
                    }
                }
            } else {
                ExecutorService executorService = Executors.newFixedThreadPool(poolSize);
                if (!CommonUtil.isArtifactIndexMapExisting()) {
                    CommonUtil.createArtifactIndexMap();
                }
                if (!CommonUtil.isSymbolicLinkMapExisting()) {
                    CommonUtil.createSymbolicLinkMap();
                }
                for (UploadTask task : tasks) {
                    executorService.submit(task);
                }
                executorService.shutdown();
                while (!executorService.isTerminated()) {

                }
            }
        } finally {
            CommonUtil.clearImportedArtifactMap();
        }
        try {
            if (CommonUtil.isArtifactIndexMapExisting()) {
                Map<String, String> artifactIndexMap =
                        CommonUtil.getAndRemoveArtifactIndexMap();

                if (log.isDebugEnabled()) {
                    for (Map.Entry<String, String> entry : artifactIndexMap.entrySet()) {
                        log.debug("Added Artifact Entry: " + entry.getKey());
                    }
                }

//                CommonUtil.addGovernanceArtifactEntriesWithRelativeValues(
//                        CommonUtil.getUnchrootedSystemRegistry(requestContext), artifactIndexMap);
            }
            Registry registry = requestContext.getRegistry();
            if (!isDisableSymlinkCreation() && CommonUtil.isSymbolicLinkMapExisting()) {
                Map<String, String> symbolicLinkMap =
                        CommonUtil.getAndRemoveSymbolicLinkMap();

                for (Map.Entry<String, String> entry : symbolicLinkMap.entrySet()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Added Symbolic Link: " + entry.getKey());
                    }
                    try {
                        if (registry.resourceExists(entry.getKey())) {
                            registry.removeLink(entry.getKey());
                        }
                    } catch (RegistryException ignored) {
                        // we are not bothered above errors in getting rid of symbolic links.
                    }
                    requestContext.getSystemRegistry().createLink(entry.getKey(), entry.getValue());
                }
            }
        } catch (RegistryException e) {
            log.error("Unable to build artifact index.", e);
        }
        Map<String, String> taskResults = new LinkedHashMap<String, String>();
        for (UploadTask task : tasks) {
            if (task.getFailed()) {
                taskResults.put(task.getUri(), null);
            } else {
                taskResults.put(task.getUri(), task.getResult());
            }
        }
        onPutCompleted(path, taskResults, uriList, requestContext);
        try {
            delete(tempFile);
            while (!fileList.isEmpty()) {
                delete(fileList.pop());
            }
            FileUtils.deleteDirectory(tempDir);
        } catch (IOException e) {
            log.error("Unable to cleanup temporary files", e);
        }
        log.info("Completed uploading files from archive file");
    }

    protected static abstract class UploadTask implements Runnable {

        private String uri;
        private RequestContext requestContext;
        private int tenantId = -1;
        private UserRegistry userRegistry;
        private UserRealm userRealm;
        private String userId;
        private int callerTenantId;
        private Map<String, String> localPathMap;
        private Random random = new Random(10);

        protected String result = null;
        protected boolean failed = false;
        protected int retries = 0;

        public UploadTask(RequestContext requestContext, String uri, int tenantId,
                          UserRegistry userRegistry, UserRealm userRealm, String userId,
                          int callerTenantId, Map<String, String> localPathMap) {
            this.userRegistry = userRegistry;
            this.userRealm = userRealm;
            this.tenantId = tenantId;
            this.requestContext = requestContext;
            this.uri = uri;
            this.userId = userId;
            this.callerTenantId = callerTenantId;
            this.localPathMap = localPathMap;
            System.out.println("LINE 856 : Uploading Files");
        }

        public void run() {
            try {
                PrivilegedCarbonContext.startTenantFlow();
                //This is for fixing CARBON-14469.
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(tenantId, true);
                // File is already uploaded via wsdl or xsd imports those are skip
                if (CommonUtil.isImportedArtifactExisting(new File(uri).toString())) {
                    failed = false;
                    result = "added from import";
                    return;
                }
                doWork();
            } finally {
                PrivilegedCarbonContext.endTenantFlow();
            }
        }

        protected void retry() {
            //Number of retry can be configurable via handler configuration (<property name="numberOfRetry">1</property>)
            if (retries < ZipHandler.numberOfRetry) {
                ++retries;
                log.info("Retrying to upload resource: " + uri);
                int i = random.nextInt(10);
                if (log.isDebugEnabled()) {
                    log.debug("Waiting for " + i + " seconds");
                }
                try {
                    Thread.sleep(1000 * i);
                } catch (InterruptedException ignored) {
                }
                doWork();
            } else {
                failed = true;
            }
        }

        private void doWork() {
            CurrentSession.setTenantId(tenantId);
            CurrentSession.setUserRegistry(userRegistry);
            CurrentSession.setUserRealm(userRealm);
            CurrentSession.setUser(userId);
            CurrentSession.setCallerTenantId(callerTenantId);
            if (localPathMap != null) {
                CurrentSession.setLocalPathMap(localPathMap);
            }
            System.out.println("LINE 904 : Uploading process");
            try {
                if (CommonUtil.isUpdateLockAvailable()) {
                    CommonUtil.acquireUpdateLock();
                    try {
                        RequestContext requestContext =
                                new RequestContext(this.requestContext.getRegistry(),
                                        this.requestContext.getRepository(),
                                        this.requestContext.getVersionRepository());
                        requestContext.setResourcePath(this.requestContext.getResourcePath());
                        requestContext.setResource(this.requestContext.getResource());
                        requestContext.setOldResource(this.requestContext.getOldResource());
                        System.out.println("LINE 916 : Uploading process");
                        doProcessing(requestContext, uri);
                    } finally {
                        CommonUtil.releaseUpdateLock();
                    }
                }
            } catch (RegistryException e) {
                log.error("An error occurred while  uploading "+uri, e);
                retry();
            } catch (RuntimeException e) {
                log.error("An unhandled exception occurred while  uploading " + uri, e);
                retry();
            } finally {
                CurrentSession.removeUser();
                CurrentSession.removeUserRealm();
                CurrentSession.removeUserRegistry();
                CurrentSession.removeTenantId();
                CurrentSession.removeCallerTenantId();
                if (localPathMap != null) {
                    CurrentSession.removeLocalPathMap();
                }
                // get rid of the reference to the request context at the end.
                requestContext = null;
            }
        }

        protected abstract void doProcessing(RequestContext requestContext, String uri)
                throws RegistryException;

        public String getUri() {
            return uri;
        }

        public String getResult() {
            return result;
        }

        public boolean getFailed() {
            return failed;
        }
    }

    public void makeDir(File file) throws IOException {
        if (file != null && !file.exists() && !file.mkdir()) {
            log.warn("Failed to create directory at path: " + file.getAbsolutePath());
        }
    }

    public void makeDirs(File file) throws IOException {
        if (file != null && !file.exists() && !file.mkdirs()) {
            log.warn("Failed to create directories at path: " + file.getAbsolutePath());
        }
    }

    public void delete(File file) throws IOException {
        if (file != null && file.exists() && !file.delete()) {
            log.warn("Failed to delete file/directory at path: " + file.getAbsolutePath());
        }
    }
    protected class UploadFileTask extends UploadTask {

        String mediaType;

        public UploadFileTask(RequestContext requestContext, String uri, int tenantId,
                              UserRegistry userRegistry, UserRealm userRealm, String userId,
                              int callerTenantId, Map<String, String> localPathMap,
                              String mediaType) {
            super(requestContext, uri, tenantId, userRegistry, userRealm, userId, callerTenantId,
                    localPathMap);
            this.mediaType = mediaType;
        }

        protected void doProcessing(RequestContext requestContext, String uri)
                throws RegistryException {
            result = requestContext.getRegistry().put(requestContext.getResourcePath().getPath(), zipResource);
        }
    }

    protected class UploadWSDLTask extends UploadTask {

        public UploadWSDLTask(RequestContext requestContext, String uri, int tenantId,
                              UserRegistry userRegistry, UserRealm userRealm, String userId,
                              int callerTenantId, Map<String, String> localPathMap) {
            super(requestContext, uri, tenantId, userRegistry, userRealm, userId, callerTenantId,
                    localPathMap);
            System.out.println("LINE 1036 : Uploading WSDLs");
        }

        protected void doProcessing(RequestContext requestContext, String uri)
                throws RegistryException {
            result = addWSDLFromZip(requestContext, uri);
        }
    }


    private void addDocument(String docNameArg, String docFilePath) {
        boolean success;
        String providerName = this.apiProvider;
        String apiName = this.apiName;
        String version = this.apiVersion;
        String docName = docNameArg;
        String visibility = null;
        String docType = DocumentationType.OTHER.getType();
        String summary = "Uploaded from a zip file";
        String sourceURL = null;

        APIIdentifier apiId = new APIIdentifier(APIUtil.replaceEmailDomain(providerName), apiName, version);
        Documentation doc = new Documentation(DocumentationType.OTHER, docName);
        if (doc.getType() == DocumentationType.OTHER) {
            doc.setOtherTypeName("Custom");
        }
        doc.setSourceType(Documentation.DocumentSourceType.FILE);
        doc.setSummary(summary);
        if(visibility==null){visibility=APIConstants.DOC_API_BASED_VISIBILITY;}
        if (visibility.equalsIgnoreCase(Documentation.DocumentVisibility.API_LEVEL.toString())) {
            doc.setVisibility(Documentation.DocumentVisibility.API_LEVEL);
        } else if (visibility.equalsIgnoreCase(Documentation.DocumentVisibility.PRIVATE.toString())) {
            doc.setVisibility(Documentation.DocumentVisibility.PRIVATE);
        } else {
            doc.setVisibility(Documentation.DocumentVisibility.OWNER_ONLY);
        }
        try {
            API api = apiProviderOb.getAPI(apiId);
            String apiPath=APIUtil.getAPIPath(apiId);
            String visibleRolesList = api.getVisibleRoles();
            String[] visibleRoles = new String[0];
            if (visibleRolesList != null) {
                visibleRoles = visibleRolesList.split(",");
            }
            APIUtil.setResourcePermissions(api.getId().getProviderName(),
                    api.getVisibility(), visibleRoles,docFilePath);
            String fullyQualifiedFilePath = RegistryConstants.PATH_SEPARATOR + "registry"
                    + RegistryConstants.PATH_SEPARATOR + "resource"+docFilePath;
            doc.setFilePath(fullyQualifiedFilePath);
            apiProviderOb.addDocumentation(apiId, doc);
            success = true;
        } catch (APIManagementException e) {
            String msg = "Error occurred while adding the document- " + docName;
            log.error(msg, e);
        }
    }
}