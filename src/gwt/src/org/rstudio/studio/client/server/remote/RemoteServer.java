/*
 * RemoteServer.java
 *
 * Copyright (C) 2009-11 by RStudio, Inc.
 *
 * This program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */

package org.rstudio.studio.client.server.remote;

import com.google.gwt.core.client.*;
import com.google.gwt.http.client.URL;
import com.google.gwt.json.client.*;
import com.google.gwt.user.client.Random;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import org.rstudio.core.client.Debug;
import org.rstudio.core.client.StringUtil;
import org.rstudio.core.client.dom.WindowEx;
import org.rstudio.core.client.files.FileSystemItem;
import org.rstudio.core.client.js.JsObject;
import org.rstudio.core.client.jsonrpc.*;
import org.rstudio.studio.client.application.Desktop;
import org.rstudio.studio.client.application.events.*;
import org.rstudio.studio.client.application.model.HttpLogEntry;
import org.rstudio.studio.client.common.codetools.Completions;
import org.rstudio.studio.client.common.console.ConsoleProcess;
import org.rstudio.studio.client.common.console.ConsoleProcess.ConsoleProcessFactory;
import org.rstudio.studio.client.common.crypto.PublicKeyInfo;
import org.rstudio.studio.client.common.mirrors.model.CRANMirror;
import org.rstudio.studio.client.common.satellite.Satellite;
import org.rstudio.studio.client.common.satellite.SatelliteManager;
import org.rstudio.studio.client.common.vcs.*;
import org.rstudio.studio.client.projects.model.RProjectOptions;
import org.rstudio.studio.client.projects.model.RProjectVcsOptions;
import org.rstudio.studio.client.projects.model.VcsCloneOptions;
import org.rstudio.studio.client.server.*;
import org.rstudio.studio.client.server.Void;
import org.rstudio.studio.client.workbench.codesearch.model.CodeSearchResults;
import org.rstudio.studio.client.workbench.codesearch.model.FunctionDefinition;
import org.rstudio.studio.client.workbench.codesearch.model.SearchPathFunctionDefinition;
import org.rstudio.studio.client.workbench.model.Agreement;
import org.rstudio.studio.client.workbench.model.Session;
import org.rstudio.studio.client.workbench.model.SessionInfo;
import org.rstudio.studio.client.workbench.model.WorkbenchMetrics;
import org.rstudio.studio.client.workbench.prefs.model.RPrefs;
import org.rstudio.studio.client.workbench.views.files.model.FileUploadToken;
import org.rstudio.studio.client.workbench.views.help.model.HelpInfo;
import org.rstudio.studio.client.workbench.views.history.model.HistoryEntry;
import org.rstudio.studio.client.workbench.views.packages.model.PackageInfo;
import org.rstudio.studio.client.workbench.views.packages.model.PackageInstallContext;
import org.rstudio.studio.client.workbench.views.packages.model.PackageUpdate;
import org.rstudio.studio.client.workbench.views.plots.model.Point;
import org.rstudio.studio.client.workbench.views.plots.model.SavePlotAsImageContext;
import org.rstudio.studio.client.workbench.views.source.editors.text.IconvListResult;
import org.rstudio.studio.client.workbench.views.source.model.CheckForExternalEditResult;
import org.rstudio.studio.client.workbench.views.source.model.PublishPdfResult;
import org.rstudio.studio.client.workbench.views.source.model.SourceDocument;
import org.rstudio.studio.client.workbench.views.vcs.dialog.CommitCount;
import org.rstudio.studio.client.workbench.views.vcs.dialog.CommitInfo;
import org.rstudio.studio.client.workbench.views.workspace.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Singleton
public class RemoteServer implements Server
{ 
   @Inject
   public RemoteServer(Session session, 
                       EventBus eventBus,
                       Satellite satellite,
                       final SatelliteManager satelliteManager,
                       Provider<ConsoleProcessFactory> pConsoleProcessFactory)
   {
      pConsoleProcessFactory_ = pConsoleProcessFactory;
      clientId_ = null;
      disconnected_ = false;
      listeningForEvents_ = false;
      session_ = session;
      eventBus_ = eventBus;
      satellite_ = satellite;
      serverAuth_ = new RemoteServerAuth(this);
      
      // define external event listener if we are the main window
      // (so we can forward to the satellites)
      ClientEventHandler externalListener = null;
      if (!satellite.isCurrentWindowSatellite())
      {
         externalListener = new ClientEventHandler() {
            @Override
            public void onClientEvent(JavaScriptObject clientEvent)
            {
               satelliteManager.dispatchEvent(clientEvent);     
            } 
         };
      }
      
      // create server event listener
      serverEventListener_ = new RemoteServerEventListener(this, 
                                                           externalListener);
   }
   
   // complete initialization now that the workbench is ready
   public void initializeForMainWorkbench()
   {
      // satellite windows should never call onWorkbenchReady
      if (satellite_.isCurrentWindowSatellite())
      {
         Debug.log("Satellite window cannot call onWorkbenchReady!");
         assert false;
      }
      
      // update state
      listeningForEvents_ = true;
      
      // only check credentials if we are in server mode
      if (session_.getSessionInfo().getMode().equals(SessionInfo.SERVER_MODE))
         serverAuth_.schedulePeriodicCredentialsUpdate();
      
      // start event listener
      serverEventListener_.start();
      
      // register satallite callback
      registerSatelliteCallback();
   }
     
   public void log(int logEntryType, 
                   String logEntry, 
                   ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONNumber(logEntryType));
      params.set(1, new JSONString(logEntry));
      sendRequest(LOG_SCOPE , LOG, params, requestCallback);
   }
    
   public void clientInit(
                     final ServerRequestCallback<SessionInfo> requestCallback)
   {      
      // send init request (record clientId and version contained in response)
      sendRequest(RPC_SCOPE, 
                  CLIENT_INIT, 
                  new ServerRequestCallback<SessionInfo>() {

         public void onResponseReceived(SessionInfo sessionInfo)
         {
            clientId_ = sessionInfo.getClientId();
            clientVersion_ = sessionInfo.getClientVersion();
            requestCallback.onResponseReceived(sessionInfo);
         }
   
         public void onError(ServerError error)
         {
            requestCallback.onError(error);
         }
      });
   }
   
   // accept application agreement
   public void acceptAgreement(Agreement agreement, 
                               ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, 
                  ACCEPT_AGREEMENT, 
                  agreement.getHash(),
                  requestCallback);
   }
   
   
   public void suspendSession(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, SUSPEND_SESSION, requestCallback);
   }
   

   public void handleUnsavedChangesCompleted(
                            boolean handled,
                            ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, 
                  HANDLE_UNSAVED_CHANGES_COMPLETED, 
                  handled, 
                  requestCallback);
   }
   
   public void quitSession(boolean saveWorkspace, 
                           String switchToProject,
                           ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, JSONBoolean.getInstance(saveWorkspace));
      params.set(1, new JSONString(StringUtil.notNull(switchToProject)));
      sendRequest(RPC_SCOPE, QUIT_SESSION, params, requestCallback);
   }
   
   public void updateCredentials()
   {
      serverAuth_.attemptToUpdateCredentials();
   }
   
   public String getApplicationURL(String pathName)
   {
      // if accessing a URL is the first thing we do after being
      // suspended ensure that events flow right away
      ensureListeningForEvents();
      
      // return the url
      return GWT.getHostPageBaseURL() + pathName;
   }
  
   
   public void setWorkbenchMetrics(WorkbenchMetrics metrics,
                                   ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, 
                  SET_WORKBENCH_METRICS, 
                  metrics, 
                  requestCallback);
   }

   public void setPrefs(RPrefs rPrefs,
                        JavaScriptObject uiPrefs,
                        ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONObject(rPrefs));
      params.set(1, new JSONObject(uiPrefs));
      sendRequest(RPC_SCOPE, SET_PREFS, params, requestCallback);
}
   
   public void setUiPrefs(JavaScriptObject uiPrefs,
                          ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  SET_UI_PREFS,
                  uiPrefs,
                  requestCallback);
   }

   public void getRPrefs(ServerRequestCallback<RPrefs> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  GET_R_PREFS,
                  requestCallback);
   }

   public void updateClientState(JavaScriptObject temporary,
                                 JavaScriptObject persistent,
                                 JavaScriptObject projectPersistent,
                                 ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONObject(temporary));
      params.set(1, new JSONObject(persistent));
      params.set(2, new JSONObject(projectPersistent));
      sendRequest(RPC_SCOPE,
                  SET_CLIENT_STATE,
                  params,
                  requestCallback);
   }
   
   public void userPromptCompleted(int response, 
                                  ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, USER_PROMPT_COMPLETED, response, requestCallback);
   }
   
   public void searchCode(
         String term, 
         int maxResults,
         ServerRequestCallback<CodeSearchResults> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(term));
      params.set(1, new JSONNumber(maxResults));
      sendRequest(RPC_SCOPE, SEARCH_CODE, params, requestCallback);
   }
   
   public void getFunctionDefinition(
         String line, 
         int pos,
         ServerRequestCallback<FunctionDefinition> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(line));
      params.set(1, new JSONNumber(pos));
      sendRequest(RPC_SCOPE, 
                  GET_FUNCTION_DEFINITION, 
                  params, 
                  requestCallback);
   }
   
   public void findFunctionInSearchPath(
         String line, 
         int pos,
         String fromWhere,
         ServerRequestCallback<SearchPathFunctionDefinition> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(line));
      params.set(1, new JSONNumber(pos));
      params.set(2, fromWhere != null ? new JSONString(fromWhere) :
                                        JSONNull.getInstance());
      sendRequest(RPC_SCOPE, 
                  FIND_FUNCTION_IN_SEARCH_PATH, 
                  params, 
                  requestCallback);
   }


   public void getSearchPathFunctionDefinition(
         String name,
         String namespace,
         ServerRequestCallback<SearchPathFunctionDefinition> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(name));
      params.set(1, new JSONString(namespace));
      sendRequest(RPC_SCOPE, 
                  GET_SEARCH_PATH_FUNCTION_DEFINITION, 
                  params, 
                  requestCallback);
   }
   
   public void getMethodDefinition(
         String name,
         ServerRequestCallback<SearchPathFunctionDefinition> requestCallback)
   {
      sendRequest(RPC_SCOPE, GET_METHOD_DEFINITION, name, requestCallback);
   }

   public void consoleInput(String consoleInput,
                            ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, CONSOLE_INPUT, consoleInput, requestCallback);
   }
   
   public void resetConsoleActions(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, RESET_CONSOLE_ACTIONS, requestCallback);
   }

   @Override
   public void processInit(String command,
                           String caption,
                           boolean dialog,
                           ServerRequestCallback<String> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(command));
      params.set(1, new JSONString(caption));
      params.set(2, JSONBoolean.getInstance(dialog));
      sendRequest(RPC_SCOPE, PROCESS_INIT, params, requestCallback);
   }

   public void processStart(String handle,
                            ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, PROCESS_START, handle, requestCallback);
   }

   @Override
   public void processInterrupt(String handle,
                                ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, PROCESS_INTERRUPT, handle, requestCallback);
   }

   @Override
   public void processReap(String handle,
                           ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, PROCESS_REAP, handle, requestCallback);
   }

   @Override
   public void processWriteStdin(String handle,
                                 String input,
                                 ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(handle));
      params.set(1, new JSONString(input));
      sendRequest(RPC_SCOPE, PROCESS_WRITE_STDIN, params, requestCallback);
   }


   public void interrupt(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, INTERRUPT, requestCallback);
   }
   
   public void abort(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, ABORT, requestCallback);
   }
   
   public void httpLog(
         ServerRequestCallback<JsArray<HttpLogEntry>> requestCallback)
   {
      sendRequest(RPC_SCOPE, HTTP_LOG, requestCallback);
   }

   public void getCompletions(String line, int cursorPos,
                          ServerRequestCallback<Completions> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(line));
      params.set(1, new JSONNumber(cursorPos));
      sendRequest(RPC_SCOPE, 
                  GET_COMPLETIONS, 
                  params, 
                  requestCallback) ;
   }

   public void getHelpAtCursor(String line, int cursorPos,
                               ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(line));
      params.set(1, new JSONNumber(cursorPos));
      sendRequest(RPC_SCOPE,
                  GET_HELP_AT_CURSOR,
                  params,
                  requestCallback) ;
   }
   
   public void listObjects(
         ServerRequestCallback<RpcObjectList<WorkspaceObjectInfo>> requestCallback)
   {
      sendRequest(RPC_SCOPE, LIST_OBJECTS, requestCallback);
   }

  
   public void removeAllObjects(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  REMOVE_ALL_OBJECTS,
                  requestCallback);
   }

   
   public void setObjectValue(String objectName,
                              String value,
                              ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(objectName));
      params.set(1, new JSONString(value));
      sendRequest(RPC_SCOPE,
                  SET_OBJECT_VALUE,
                  params,
                  requestCallback);
   }

   public void getObjectValue(String objectName,
                              ServerRequestCallback<RpcObjectList<WorkspaceObjectInfo>> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  GET_OBJECT_VALUE,
                  objectName,
                  requestCallback);
   }

 
   public void listGoogleSpreadsheets(
         String titlePattern,             // null for all spreadsheets
         int maxResults,
         ServerRequestCallback<JsArray<GoogleSpreadsheetInfo>> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, titlePattern != null ? new JSONString(titlePattern) :
                                           JSONNull.getInstance());
      params.set(1, new JSONNumber(maxResults));
      sendRequest(RPC_SCOPE, 
                  LIST_GOOGLE_SPREADSHEETS, 
                  params, 
                  requestCallback) ;
   }
   
   public void importGoogleSpreadsheet(
                                GoogleSpreadsheetImportSpec importSpec,
                                ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, 
                  IMPORT_GOOGLE_SPREADSHEET, 
                  importSpec, 
                  requestCallback);
   }
   
   public void downloadDataFile(
                  String dataFileUrl,
                  ServerRequestCallback<DownloadInfo> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  DOWNLOAD_DATA_FILE,
                  dataFileUrl,
                  requestCallback);
   }

   public void getDataPreview(String dataFilePath,
                              ServerRequestCallback<DataPreviewResult> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  GET_DATA_PREVIEW,
                  dataFilePath,
                  requestCallback);
   }

   public void getOutputPreview(String dataFilePath,
                                boolean heading,
                                String separator,
                                String decimal,
                                String quote,
                                ServerRequestCallback<DataPreviewResult> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(dataFilePath));
      params.set(1, JSONBoolean.getInstance(heading));
      params.set(2, new JSONString(separator));
      params.set(3, new JSONString(decimal));
      params.set(4, new JSONString(quote));

      sendRequest(RPC_SCOPE,
                  GET_OUTPUT_PREVIEW,
                  params,
                  requestCallback);
   }

   public void editCompleted(String text,
                             ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, EDIT_COMPLETED, text, requestCallback);
   }
   
   public void chooseFileCompleted(String file, 
                                   ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, CHOOSE_FILE_COMPLETED, file, requestCallback);
   }


   public void listPackages(
         ServerRequestCallback<JsArray<PackageInfo>> requestCallback)
   {
      sendRequest(RPC_SCOPE, LIST_PACKAGES, requestCallback);
   }
   
   public void getPackageInstallContext(
               ServerRequestCallback<PackageInstallContext> requestCallback)
   {
      sendRequest(RPC_SCOPE, GET_PACKAGE_INSTALL_CONTEXT, requestCallback);
   }
   
   public void isPackageLoaded(String packageName,
                               ServerRequestCallback<Boolean> requestCallback)
   {
      sendRequest(RPC_SCOPE, IS_PACKAGE_LOADED, packageName, requestCallback);
   }
   
   public void availablePackages(
         String repository,
         ServerRequestCallback<JsArrayString> requestCallback)
   {
      sendRequest(RPC_SCOPE, AVAILABLE_PACKAGES, repository, requestCallback);
   }
   
   public void checkForPackageUpdates(
         ServerRequestCallback<JsArray<PackageUpdate>> requestCallback)
   {
      sendRequest(RPC_SCOPE, CHECK_FOR_PACKAGE_UPDATES, requestCallback);
   }
   
   public void initDefaultUserLibrary(
                              ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, INIT_DEFAULT_USER_LIBRARY, requestCallback);
   }

   public void setCRANMirror(CRANMirror mirror,
                             ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, SET_CRAN_MIRROR, mirror, requestCallback);
   }
   
   public void getCRANMirrors(
                  ServerRequestCallback<JsArray<CRANMirror>> requestCallback)
   {
      sendRequest(RPC_SCOPE, GET_CRAN_MIRRORS, requestCallback);
   }

   public void suggestTopics(String prefix,
                             ServerRequestCallback<JsArrayString> requestCallback)
   {
      sendRequest(RPC_SCOPE, "suggest_topics", prefix, requestCallback);
   }

   public void getHelp(String topic,
                       String packageName,
                       int options,
                       ServerRequestCallback<HelpInfo> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(topic));
      if (packageName != null)
         params.set(1, new JSONString(packageName));
      else
         params.set(1, JSONNull.getInstance());
      params.set(2, new JSONNumber(options));
      
      sendRequest(RPC_SCOPE, GET_HELP, params, requestCallback);
   }
   
   public void showHelpTopic(String topic, String pkgName)
   {
      JSONArray params = new JSONArray() ;
      params.set(0, new JSONString(topic)) ;
      params.set(1, pkgName != null 
                       ? new JSONString(pkgName)
                       : JSONNull.getInstance()) ;
      
      sendRequest(RPC_SCOPE,
                  SHOW_HELP_TOPIC,
                  params,
                  null) ;
   }
   
   public void search(String query, 
                      ServerRequestCallback<JsArrayString> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  SEARCH,
                  query,
                  requestCallback) ;
   }
   
   @Override
   public void stat(String path,
                    ServerRequestCallback<FileSystemItem> requestCallback)
   {
      sendRequest(RPC_SCOPE, STAT, path, requestCallback);
   }

   public void listFiles(
                  FileSystemItem directory,
                  boolean monitor,
                  ServerRequestCallback<JsArray<FileSystemItem>> requestCallback)
   {
      JSONArray paramArray = new JSONArray();
      paramArray.set(0, new JSONString(directory.getPath()));
      paramArray.set(1, JSONBoolean.getInstance(monitor));
      
      sendRequest(RPC_SCOPE, 
                  LIST_FILES, 
                  paramArray, 
                  requestCallback);    
   }

   public void listAllFiles(String path,
                            String pattern,
                            ServerRequestCallback<JsArrayString> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(path));
      params.set(1, new JSONString(pattern));
      sendRequest(RPC_SCOPE,
                  LIST_ALL_FILES,
                  params,
                  requestCallback);
   }

   public void createFolder(FileSystemItem folder,
                            ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, 
                  CREATE_FOLDER, 
                  folder.getPath(), 
                  requestCallback);
   }

   public void deleteFiles(ArrayList<FileSystemItem> files,
                           ServerRequestCallback<Void> requestCallback)
   {
      JSONArray paramArray = new JSONArray();
      JSONArray pathArray = new JSONArray();
      for (int i=0; i<files.size(); i++)
         pathArray.set(i, new JSONString(files.get(i).getPath()));
      paramArray.set(0, pathArray);

      sendRequest(RPC_SCOPE, DELETE_FILES, paramArray, requestCallback);
   }
   
   public void copyFile(FileSystemItem sourceFile,
                        FileSystemItem targetFile,
                        ServerRequestCallback<Void> requestCallback)
   {
      JSONArray paramArray = new JSONArray();
      paramArray.set(0, new JSONString(sourceFile.getPath()));
      paramArray.set(1, new JSONString(targetFile.getPath()));
      
      sendRequest(RPC_SCOPE, COPY_FILE, paramArray, requestCallback);
   }


   public void moveFiles(ArrayList<FileSystemItem> files,
                         FileSystemItem targetDirectory,
                         ServerRequestCallback<Void> requestCallback)
   {
      JSONArray paramArray = new JSONArray();

      JSONArray pathArray = new JSONArray();
      for (int i=0; i<files.size(); i++)
         pathArray.set(i, new JSONString(files.get(i).getPath()));

      paramArray.set(0, pathArray);
      paramArray.set(1, new JSONString(targetDirectory.getPath()));

      sendRequest(RPC_SCOPE, MOVE_FILES, paramArray, requestCallback);
   }

   public void renameFile(FileSystemItem file, 
                          FileSystemItem targetFile,
                          ServerRequestCallback<Void> requestCallback)
   {
      JSONArray paramArray = new JSONArray();
      paramArray.set(0, new JSONString(file.getPath()));
      paramArray.set(1, new JSONString(targetFile.getPath()));

      sendRequest(RPC_SCOPE, RENAME_FILE, paramArray, requestCallback);
   }

   public String getFileUrl(FileSystemItem file)
   {
      if (Desktop.isDesktop())
      {
         return Desktop.getFrame().getUriForPath(file.getPath());
      }
      
      if (!file.isDirectory())
      {
         if (file.isWithinHome())
         {
            return getApplicationURL(FILES_SCOPE) + "/" + file.homeRelativePath();
         }
         else
         {
            String url = getApplicationURL(FILE_SHOW);
            url += "?path=" + URL.encodeQueryString(file.getPath());
            return url;
         }  
      }
      else
      {
         return null;
      }
   }
   
   // get file upload base url
   public String getFileUploadUrl()
   {
      return getApplicationURL(UPLOAD_SCOPE);
   }
      
   public void completeUpload(FileUploadToken token,
                              boolean commit,
                              ServerRequestCallback<Void> requestCallback)
   {
      JSONArray paramArray = new JSONArray();
      paramArray.set(0, new JSONObject(token));
      paramArray.set(1, JSONBoolean.getInstance(commit));
      sendRequest(RPC_SCOPE, COMPLETE_UPLOAD, paramArray, requestCallback);
   }
   
   public String getFileExportUrl(String name, FileSystemItem file)
   {
      return getApplicationURL(EXPORT_SCOPE) + "/" + URL.encodePathSegment(name) + "?" +
         "name=" + URL.encodeQueryString(name) + "&" +
         "file=" + URL.encodeQueryString(file.getPath());
   }
   
   
   public String getFileExportUrl(String name,
                                  FileSystemItem parentDirectory,
                                  ArrayList<String> filenames)
   {
      // build url params for files
      StringBuilder files = new StringBuilder();
      for (int i = 0; i<filenames.size(); i++)
      {
         files.append("file").append(i).append("=");
         files.append(URL.encodeQueryString(filenames.get(i)));
         files.append("&");
      }
         
      // return url
      return getApplicationURL(EXPORT_SCOPE) + "/" + URL.encodePathSegment(name) + "?" +
        "name=" + URL.encodeQueryString(name) + "&" +
        "parent=" + URL.encodeQueryString(parentDirectory.getPath()) + "&" +
         files.toString();
   }
   
   
   // get graphics url
   public String getGraphicsUrl(String filename)
   {
      return getApplicationURL(GRAPHICS_SCOPE) + "/" + filename;
   }
   
   public String getPlotExportUrl(String type, 
                                  int width, 
                                  int height, 
                                  boolean attachment)
   {
      // build preview URL
      String previewURL = getGraphicsUrl("plot." + type);
      previewURL += "?";
      previewURL += "width=" + width;
      previewURL += "&";
      previewURL += "height=" + height;
      // append random number to default over-aggressive image caching
      // by browsers
      previewURL += "&randomizer=" + Random.nextInt();
      if (attachment)
         previewURL += "&attachment=1";
      
      return previewURL;
   }
   
   public void nextPlot(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, NEXT_PLOT, requestCallback);
   }
   
   public void previousPlot(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, PREVIOUS_PLOT, requestCallback);
   }
   
   public void removePlot(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, REMOVE_PLOT, requestCallback);
   }
   
   public void clearPlots(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, CLEAR_PLOTS, requestCallback);
   }
   
   public void refreshPlot(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, REFRESH_PLOT, requestCallback);
   }
   
   public void savePlotAs(FileSystemItem file,
                          String format,
                          int width,
                          int height,
                          boolean overwrite,
                          ServerRequestCallback<Bool> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(file.getPath()));
      params.set(1, new JSONString(format));
      params.set(2, new JSONNumber(width));
      params.set(3, new JSONNumber(height));
      params.set(4, JSONBoolean.getInstance(overwrite));
      sendRequest(RPC_SCOPE, SAVE_PLOT_AS, params, requestCallback);
   }
   
   public void savePlotAsPdf(FileSystemItem file,
                             double widthInches,
                             double heightInches,
                             boolean overwrite,
                             ServerRequestCallback<Bool> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(file.getPath()));
      params.set(1, new JSONNumber(widthInches));
      params.set(2, new JSONNumber(heightInches));
      params.set(3, JSONBoolean.getInstance(overwrite));
      sendRequest(RPC_SCOPE, SAVE_PLOT_AS_PDF, params, requestCallback);
   }
   
   public void copyPlotToClipboardMetafile(
                              int width, 
                              int height,
                              ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONNumber(width));
      params.set(1, new JSONNumber(height));
      sendRequest(RPC_SCOPE, 
                  COPY_PLOT_TO_CLIPBOARD_METAFILE, 
                  params, 
                  requestCallback);
   }
   
   public void getUniqueSavePlotStem(String directory,
                                  ServerRequestCallback<String> requestCallback)
   {
      sendRequest(RPC_SCOPE, GET_UNIQUE_SAVE_PLOT_STEM, directory, requestCallback);
   }
   
   public void getSavePlotContext(
                  String directory,
                  ServerRequestCallback<SavePlotAsImageContext> requestCallback)
   {
      sendRequest(RPC_SCOPE, 
                  GET_SAVE_PLOT_CONTEXT, 
                  directory, 
                  requestCallback);
   }

   public void locatorCompleted(Point point,
                                ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, LOCATOR_COMPLETED, point, requestCallback);
   }
   
   public void setManipulatorValues(JSONObject values,
                                    ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, values);
      sendRequest(RPC_SCOPE, SET_MANIPULATOR_VALUES, params, requestCallback);
   }
   
   public void manipulatorPlotClicked(
                                 int x, 
                                 int y, 
                                 ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONNumber(x));
      params.set(1, new JSONNumber(y));
      sendRequest(RPC_SCOPE, MANIPULATOR_PLOT_CLICKED, params, requestCallback);
   }
   
   public void createProject(String projectDirectory,
                             ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, CREATE_PROJECT, projectDirectory, requestCallback);
   }
   
   public void readProjectOptions(ServerRequestCallback<RProjectOptions> callback)
   {
      sendRequest(RPC_SCOPE, READ_PROJECT_OPTIONS, callback);
   }
   
   public void writeProjectOptions(RProjectOptions options,
                                  ServerRequestCallback<Void> callback)
   {
      sendRequest(RPC_SCOPE, WRITE_PROJECT_OPTIONS, options, callback);
   }
   
   public void writeProjectVcsOptions(RProjectVcsOptions options,
                                      ServerRequestCallback<Void> callback)
   {
      sendRequest(RPC_SCOPE, WRITE_PROJECT_VCS_OPTIONS, options, callback);
   }
   
   public void newDocument(String filetype,
                           JsObject properties,
                           ServerRequestCallback<SourceDocument> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(filetype));
      params.set(1, new JSONObject(properties));
      sendRequest(RPC_SCOPE, NEW_DOCUMENT, params, requestCallback);
   }

   public void openDocument(String path,
                            String filetype,
                            String encoding,
                            ServerRequestCallback<SourceDocument> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(path));
      params.set(1, new JSONString(filetype));
      params.set(2, encoding != null ? new JSONString(encoding) 
                                     : JSONNull.getInstance());
      sendRequest(RPC_SCOPE, OPEN_DOCUMENT, params, requestCallback);
   }

   public void saveDocument(String id,
                            String path,
                            String fileType,
                            String encoding,
                            String contents,
                            ServerRequestCallback<String> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(id));
      params.set(1, path == null ? JSONNull.getInstance() : new JSONString(path));
      params.set(2, fileType == null ? JSONNull.getInstance() : new JSONString(fileType));
      params.set(3, encoding == null ? JSONNull.getInstance() : new JSONString(encoding));
      params.set(4, new JSONString(contents));
      sendRequest(RPC_SCOPE, SAVE_DOCUMENT, params, requestCallback);
   }

   public void saveDocumentDiff(String id,
                                String path,
                                String fileType,
                                String encoding,
                                String replacement,
                                int offset,
                                int length,
                                String hash,
                                ServerRequestCallback<String> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(id));
      params.set(1, path == null ? JSONNull.getInstance() : new JSONString(path));
      params.set(2, fileType == null ? JSONNull.getInstance() : new JSONString(fileType));
      params.set(3, encoding == null ? JSONNull.getInstance() : new JSONString(encoding));
      params.set(4, new JSONString(replacement));
      params.set(5, new JSONNumber(offset));
      params.set(6, new JSONNumber(length));
      params.set(7, new JSONString(hash));
      sendRequest(RPC_SCOPE, SAVE_DOCUMENT_DIFF, params, requestCallback);
   }

   public void checkForExternalEdit(
         String id,
         ServerRequestCallback<CheckForExternalEditResult> requestCallback)
   {
      sendRequest(RPC_SCOPE, CHECK_FOR_EXTERNAL_EDIT, id, requestCallback);
   }

   public void ignoreExternalEdit(String id,
                                  ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, IGNORE_EXTERNAL_EDIT, id, requestCallback);
   }

   public void closeDocument(String id,
                             ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, CLOSE_DOCUMENT, id, requestCallback);
   }

   public void closeAllDocuments(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, CLOSE_ALL_DOCUMENTS, requestCallback);
   }

   public void setSourceDocumentOnSave(String id,
                                       boolean shouldSourceOnSave,
                                       ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(id));
      params.set(1, JSONBoolean.getInstance(shouldSourceOnSave));
      sendRequest(RPC_SCOPE,
                  SET_SOURCE_DOCUMENT_ON_SAVE,
                  params,
                  requestCallback);
   }
   
   public void publishPdf(String id,
                          String title,
                          boolean update,
                          ServerRequestCallback<PublishPdfResult> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(id));
      params.set(1, new JSONString(title));
      params.set(2, JSONBoolean.getInstance(update));
      
      sendRequest(RPC_SCOPE, PUBLISH_PDF, params, requestCallback);
   }

   public void isTexInstalled(ServerRequestCallback<Boolean> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  IS_TEX_INSTALLED,
                  requestCallback);
   }
   
   public String getProgressUrl(String message)
   {
      String url = getApplicationURL(SOURCE_SCOPE + "/" + "progress");
      url += "?message=" + URL.encodeQueryString(message);
      return url;
   }
   
  
   public void saveActiveDocument(String contents,
                                  boolean sweave,
                                  ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(contents));
      params.set(1, JSONBoolean.getInstance(sweave));

      sendRequest(RPC_SCOPE,
                  SAVE_ACTIVE_DOCUMENT,
                  params,
                  requestCallback);
   }

   public void modifyDocumentProperties(
         String id,
         HashMap<String, String> properties,
         ServerRequestCallback<Void> requestCallback)
   {
      JSONObject obj = new JSONObject();
      for (Map.Entry<String, String> entry : properties.entrySet())
      {
         obj.put(entry.getKey(), entry.getValue() == null 
                                 ? JSONNull.getInstance()
                                 : new JSONString(entry.getValue()));
      }

      JSONArray params = new JSONArray();
      params.set(0, new JSONString(id));
      params.set(1, obj);

      sendRequest(RPC_SCOPE, MODIFY_DOCUMENT_PROPERTIES, params, requestCallback);
   }

   public void revertDocument(String id,
                              String fileType,
                              ServerRequestCallback<SourceDocument> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(id));
      params.set(1, new JSONString(fileType));
      sendRequest(RPC_SCOPE, REVERT_DOCUMENT, params, requestCallback);
   }
   
   public void reopenWithEncoding(String id,
                              String encoding,
                              ServerRequestCallback<SourceDocument> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(id));
      params.set(1, new JSONString(encoding));
      sendRequest(RPC_SCOPE, REOPEN_WITH_ENCODING, params, requestCallback);
   }

   public void removeContentUrl(String contentUrl,
                                ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, REMOVE_CONTENT_URL, contentUrl, requestCallback);
   }

   public void detectFreeVars(String code,
                              ServerRequestCallback<JsArrayString> requestCallback)
   {
      sendRequest(RPC_SCOPE, DETECT_FREE_VARS, code, requestCallback);
   }

   public void iconvlist(ServerRequestCallback<IconvListResult> requestCallback)
   {
      sendRequest(RPC_SCOPE, ICONVLIST, requestCallback);      
   }
   
   
   public void getRecentHistory(
         long maxItems,
         ServerRequestCallback<RpcObjectList<HistoryEntry>> requestCallback)
   {
      sendRequest(RPC_SCOPE, GET_RECENT_HISTORY, maxItems, requestCallback);
   }
   
   public void getHistoryItems(
         long startIndex, // inclusive
         long endIndex, // exclusive
         ServerRequestCallback<RpcObjectList<HistoryEntry>> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONNumber(startIndex));
      params.set(1, new JSONNumber(endIndex));
      sendRequest(RPC_SCOPE, GET_HISTORY_ITEMS, params, requestCallback);
   }
   
   
   public void removeHistoryItems(JsArrayNumber itemIndexes, 
                                  ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, 
                  REMOVE_HISTORY_ITEMS, 
                  itemIndexes, 
                  requestCallback);
   }
   
  
   public void clearHistory(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, CLEAR_HISTORY, requestCallback);
   }
   
   
   public void getHistoryArchiveItems(
         long startIndex, // inclusive
         long endIndex,   // exclusive
         ServerRequestCallback<RpcObjectList<HistoryEntry>> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONNumber(startIndex));
      params.set(1, new JSONNumber(endIndex));
      sendRequest(RPC_SCOPE, GET_HISTORY_ARCHIVE_ITEMS, params, requestCallback);
   }
   
  
   public void searchHistoryArchive(
         String query, 
         long maxEntries,
         ServerRequestCallback<RpcObjectList<HistoryEntry>> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(query));
      params.set(1, new JSONNumber(maxEntries));
      sendRequest(RPC_SCOPE, SEARCH_HISTORY_ARCHIVE, params, requestCallback);
   }
   
   public void searchHistoryArchiveByPrefix(
         String prefix,
         long maxEntries,
         ServerRequestCallback<RpcObjectList<HistoryEntry>> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(prefix));
      params.set(1, new JSONNumber(maxEntries));
      sendRequest(RPC_SCOPE, SEARCH_HISTORY_ARCHIVE_BY_PREFIX, params, requestCallback);
   }

   private JSONArray toJSONStringArray(ArrayList<String> paths)
   {
      JSONArray jsonPaths = new JSONArray();
      for (int i = 0; i < paths.size(); i++)
         jsonPaths.set(i, new JSONString(paths.get(i)));
      return jsonPaths;
   }

   public void gitAdd(ArrayList<String> paths,
                      ServerRequestCallback<Void> requestCallback)
   {
      JSONArray jsonPaths = toJSONStringArray(paths);

      JSONArray params = new JSONArray();
      params.set(0, jsonPaths);
      sendRequest(RPC_SCOPE, GIT_ADD, params, requestCallback);
   }

   public void gitRemove(ArrayList<String> paths,
                         ServerRequestCallback<Void> requestCallback)
   {
      JSONArray jsonPaths = toJSONStringArray(paths);

      JSONArray params = new JSONArray();
      params.set(0, jsonPaths);
      sendRequest(RPC_SCOPE, GIT_REMOVE, params, requestCallback);
   }

   public void gitDiscard(ArrayList<String> paths,
                          ServerRequestCallback<Void> requestCallback)
   {
      JSONArray jsonPaths = toJSONStringArray(paths);

      JSONArray params = new JSONArray();
      params.set(0, jsonPaths);
      sendRequest(RPC_SCOPE, GIT_DISCARD, params, requestCallback);
   }

   public void gitRevert(ArrayList<String> paths,
                         ServerRequestCallback<Void> requestCallback)
   {
      JSONArray jsonPaths = toJSONStringArray(paths);

      JSONArray params = new JSONArray();
      params.set(0, jsonPaths);
      sendRequest(RPC_SCOPE, GIT_REVERT, params, requestCallback);
   }

   public void gitStage(ArrayList<String> paths,
                        ServerRequestCallback<Void> requestCallback)
   {
      JSONArray jsonPaths = toJSONStringArray(paths);

      JSONArray params = new JSONArray();
      params.set(0, jsonPaths);
      sendRequest(RPC_SCOPE, GIT_STAGE, params, requestCallback);
   }

   public void gitUnstage(ArrayList<String> paths,
                          ServerRequestCallback<Void> requestCallback)
   {
      JSONArray jsonPaths = toJSONStringArray(paths);

      JSONArray params = new JSONArray();
      params.set(0, jsonPaths);
      sendRequest(RPC_SCOPE, GIT_UNSTAGE, params, requestCallback);
   }

   @Override
   public void gitAllStatus(ServerRequestCallback<AllStatus> requestCallback)
   {
      sendRequest(RPC_SCOPE, GIT_ALL_STATUS, requestCallback);
   }

   @Override
   public void gitFullStatus(ServerRequestCallback<JsArray<StatusAndPathInfo>> requestCallback)
   {
      sendRequest(RPC_SCOPE, GIT_FULL_STATUS, requestCallback);
   }

   @Override
   public void gitListBranches(ServerRequestCallback<BranchesInfo> requestCallback)
   {
      sendRequest(RPC_SCOPE, GIT_LIST_BRANCHES, requestCallback);
   }

   @Override
   public void gitCheckout(String id,
                           ServerRequestCallback<ConsoleProcess> requestCallback)
   {
      sendRequest(RPC_SCOPE, GIT_CHECKOUT, id,
                  new ConsoleProcessCallbackAdapter(requestCallback));
   }

   public void gitCommit(String message,
                         boolean amend,
                         boolean signOff,
                         ServerRequestCallback<ConsoleProcess> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(message));
      params.set(1, JSONBoolean.getInstance(amend));
      params.set(2, JSONBoolean.getInstance(signOff));
      sendRequest(RPC_SCOPE, GIT_COMMIT, params,
                  new ConsoleProcessCallbackAdapter(requestCallback));
   }

   private class ConsoleProcessCallbackAdapter
         extends ServerRequestCallback<String>
   {
      private ConsoleProcessCallbackAdapter(
            ServerRequestCallback<ConsoleProcess> callback)
      {
         callback_ = callback;
      }

      @Override
      public void onResponseReceived(String response)
      {
         pConsoleProcessFactory_.get().connectToProcess(response,
                                                        callback_);
      }

      @Override
      public void onError(ServerError error)
      {
         callback_.onError(error);
      }

      private final ServerRequestCallback<ConsoleProcess> callback_;
   }

   public void gitPush(ServerRequestCallback<ConsoleProcess> requestCallback)
   {
      sendRequest(RPC_SCOPE, GIT_PUSH,
                  new ConsoleProcessCallbackAdapter(requestCallback));
   }

   @Override
   public void gitClone(VcsCloneOptions options,
                        ServerRequestCallback<ConsoleProcess> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  GIT_CLONE,
                  options,
                  new ConsoleProcessCallbackAdapter(requestCallback));
   }

   public void gitPull(ServerRequestCallback<ConsoleProcess> requestCallback)
   {
      sendRequest(RPC_SCOPE, GIT_PULL,
                  new ConsoleProcessCallbackAdapter(requestCallback));
   }

   @Override
   public void askpassCompleted(String value, boolean remember,
                                ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, value == null ? JSONNull.getInstance()
                                  : new JSONString(value));
      params.set(1, JSONBoolean.getInstance(remember));
      sendRequest(RPC_SCOPE, ASKPASS_COMPLETED, params, true, requestCallback);
   }

   @Override
   public void gitSshPublicKey(String privateKeyPath,
                               ServerRequestCallback<String> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  GIT_SSH_PUBLIC_KEY,
                  privateKeyPath,
                  requestCallback);
   }

   @Override
   public void gitHasRepo(String directory,
                          ServerRequestCallback<Boolean> requestCallback)
   {
      sendRequest(RPC_SCOPE, GIT_HAS_REPO, directory, requestCallback);
   }

   @Override
   public void gitInitRepo(String directory,
                           ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, GIT_INIT_REPO, directory, requestCallback);
   }

   @Override
   public void gitDiffFile(String path,
                           PatchMode mode,
                           int contextLines,
                           boolean noSizeWarning,
                           ServerRequestCallback<String> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(path));
      params.set(1, new JSONNumber(mode.getValue()));
      params.set(2, new JSONNumber(contextLines));
      params.set(3, JSONBoolean.getInstance(noSizeWarning));
      sendRequest(RPC_SCOPE, GIT_DIFF_FILE, params, requestCallback);
   }

   @Override
   public void gitApplyPatch(String patch,
                             PatchMode mode,
                             ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(patch));
      params.set(1, new JSONNumber(mode.getValue()));
      sendRequest(RPC_SCOPE, GIT_APPLY_PATCH, params, requestCallback);
   }

   public void gitHistoryCount(String spec,
                               String filterText,
                               ServerRequestCallback<CommitCount> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(spec));
      params.set(1, new JSONString(filterText));
      sendRequest(RPC_SCOPE, GIT_HISTORY_COUNT, params, requestCallback);
   }

   @Override
   public void gitHistory(String spec,
                          int skip,
                          int maxentries,
                          String filter,
                          ServerRequestCallback<RpcObjectList<CommitInfo>> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(spec));
      params.set(1, new JSONNumber(skip));
      params.set(2, new JSONNumber(maxentries));
      params.set(3, new JSONString(StringUtil.notNull(filter)));
      sendRequest(RPC_SCOPE, GIT_HISTORY, params, requestCallback);
   }

   @Override
   public void gitExecuteCommand(String command,
                                 ServerRequestCallback<ConsoleProcess> requestCallback)
   {
      sendRequest(RPC_SCOPE, GIT_EXECUTE_COMMAND, command,
                  new ConsoleProcessCallbackAdapter(requestCallback));
   }

   @Override
   public void gitShow(String rev,
                       boolean noSizeWarning,
                       ServerRequestCallback<String> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(rev));
      params.set(1, JSONBoolean.getInstance(noSizeWarning));

      sendRequest(RPC_SCOPE, GIT_SHOW, params, requestCallback);
   }

   @Override
   public void getPublicKey(ServerRequestCallback<PublicKeyInfo> requestCallback)
   {
      sendRequest(RPC_SCOPE, GET_PUBLIC_KEY, requestCallback);
   }

   @Override
   public void listGet(String listName,
                       ServerRequestCallback<JsArrayString> requestCallback)
   {
      sendRequest(RPC_SCOPE, LIST_GET, listName, requestCallback);
   }

   @Override
   public void listPrependItem(String listName,
                               String value,
                               ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  LIST_PREPEND_ITEM,
                  listName,
                  value,
                  requestCallback);
   }

   @Override
   public void listAppendItem(String listName,
                              String value,
                              ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  LIST_APPEND_ITEM,
                  listName,
                  value,
                  requestCallback);
   }

   @Override
   public void listRemoveItem(String listName,
                              String value,
                              ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE,
                  LIST_REMOVE_ITEM,
                  listName,
                  value,
                  requestCallback);
   }

   @Override
   public void listClear(String listName,
                         ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, LIST_CLEAR, listName, requestCallback);
   }
   
   @Override
   public void startPosixShell(
                         int width,
                         int maxLines,
                         ServerRequestCallback<PublicKeyInfo> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONNumber(width));
      params.set(1, new JSONNumber(maxLines));
      sendRequest(RPC_SCOPE, START_POSIX_SHELL, params, requestCallback);
   }
   
   @Override
   public void interruptPosixShell(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, INTERRUPT_POSIX_SHELL, requestCallback);
   }
   
   @Override
   public void sendInputToPosixShell(
                              String input, 
                              ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, SEND_INPUT_TO_POSIX_SHELL, input, requestCallback);
   }
   
   @Override
   public void terminatePosixShell(ServerRequestCallback<Void> requestCallback)
   {
      sendRequest(RPC_SCOPE, TERMINATE_POSIX_SHELL, requestCallback);
   }

   // package-visible methods for peer classes RemoteServerAuth and
   // RemoveServerEventListener


   EventBus getEventBus()
   {
      return eventBus_;
   }

   RpcRequest getEvents(
                  int lastEventId,
                  ServerRequestCallback<JsArray<ClientEvent>> requestCallback,
                  RetryHandler retryHandler)
   {
      // satellite windows should never call getEvents directly!
      if (satellite_.isCurrentWindowSatellite())
      {
         Debug.log("Satellite window shoudl not call getEvents!");
         assert false;
      }
      
      JSONArray params = new JSONArray();
      params.set(0, new JSONNumber(lastEventId));
      return sendRequest(EVENTS_SCOPE,
                         "get_events",
                         params,
                         false,
                         requestCallback,
                         retryHandler);
   }

   void handleUnauthorizedError()
   {
      // disconnect
      disconnect();

      // fire event
      UnauthorizedEvent event = new UnauthorizedEvent();
      eventBus_.fireEvent(event);
   }

   private <T> void sendRequest(String scope,
                                String method,
                                ServerRequestCallback<T> requestCallback)
   {
      sendRequest(scope, method, new JSONArray(), requestCallback);
   }

   private <T> void sendRequest(String scope,
                                String method,
                                boolean param,
                                ServerRequestCallback<T> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, JSONBoolean.getInstance(param));
      sendRequest(scope, method, params, requestCallback);
   }

   private <T> void sendRequest(String scope,
                                String method,
                                long param,
                                ServerRequestCallback<T> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONNumber(param));
      sendRequest(scope, method, params, requestCallback);
   }

   private <T> void sendRequest(String scope,
                                String method,
                                String param,
                                ServerRequestCallback<T> requestCallback)
   {
      JSONArray params = new JSONArray();

      // pass JSONNull if the string is null
      params.set(0, param != null ?
                     new JSONString(param) :
                     JSONNull.getInstance());

      sendRequest(scope, method, params, requestCallback);
   }

   private <T> void sendRequest(String scope,
                                String method,
                                String param1,
                                String param2,
                                ServerRequestCallback<T> requestCallback)
   {
      JSONArray params = new JSONArray();

      // pass JSONNull if the string is null
      params.set(0, param1 != null ? new JSONString(param1) :
                                    JSONNull.getInstance());
      params.set(1, param2 != null ? new JSONString(param2) :
                                    JSONNull.getInstance());


      sendRequest(scope, method, params, requestCallback);
   }


   private <T> void sendRequest(String scope,
                                String method,
                                JavaScriptObject param,
                                ServerRequestCallback<T> requestCallback)
   {
      JSONArray params = new JSONArray();

      // pass JSONNull if the object is null
      params.set(0, param != null ? new JSONObject(param) :
                                    JSONNull.getInstance());

      sendRequest(scope, method, params, requestCallback);
   }


   private <T> void sendRequest(final String scope,
                                final String method,
                                final JSONArray params,
                                final ServerRequestCallback<T> requestCallback)
   {
      sendRequest(scope, method, params, false, requestCallback);
   }

   private <T> void sendRequest(final String scope,
                                final String method,
                                final JSONArray params,
                                final boolean redactLog,
                                final ServerRequestCallback<T> cb)
   {
      // if this is a satellite window then we handle this by proxying
      // back through the main workbench window
      if (satellite_.isCurrentWindowSatellite())
      {
         sendRequestViaMainWorkbench(scope, method, params, redactLog, cb);

      }
      // otherwise just a standard request with single retry
      else
      {
         sendRequestWithRetry(scope, method, params, redactLog, cb); 
      }
      
   }
   
   private <T> void sendRequestWithRetry(
                                 final String scope,
                                 final String method,
                                 final JSONArray params,
                                 final boolean redactLog,
                                 final ServerRequestCallback<T> requestCallback)
   {
      // retry handler (make the same call with the same params. ensure that
      // only one retry occurs by passing null as the retryHandler)
      RetryHandler retryHandler = new RetryHandler() {

         public void onRetry()
         {
            // retry one time (passing null as last param ensures there
            // is no retry handler installed)
            sendRequest(scope,
                        method, 
                        params, 
                        redactLog, 
                        requestCallback, 
                        null);
         }   

         public void onError(RpcError error)
         {
            // propagate error which caused the retry to the caller
            requestCallback.onError(new RemoteServerError(error));
         }
      };

      // submit request (retry same request up to one time)
      sendRequest(scope, 
                  method, 
                  params, 
                  redactLog, 
                  requestCallback, 
                  retryHandler);
   }

   // sendRequest method called for internal calls from main workbench
   // (as opposed to proxied calls from satellites)
   private <T> RpcRequest sendRequest(
                              String scope,
                              String method,
                              JSONArray params,
                              boolean redactLog,
                              final ServerRequestCallback<T> requestCallback,
                              RetryHandler retryHandler)
   { 
      return sendRequest(
            null,
            scope,
            method,
            params,
            redactLog,
            new RpcResponseHandler() 
            {
               @Override
               public void onResponseReceived(RpcResponse response)
               {
                  // ignore response if no request callback or
                  // if it was cancelled
                  if (requestCallback == null ||
                      requestCallback.cancelled())
                     return;
                  
                  if (response.getError() != null)
                  {
                     requestCallback.onError(
                      new RemoteServerError(response.getError()));
                  }
                  else
                  {
                     T result = response.<T> getResult();
                     requestCallback.onResponseReceived(result);
                  }
               }
             },
             retryHandler);

   }
      
   // lowest level sendRequest method -- called from the main workbench
   // in two scenarios: direct internal call and servicing a proxied
   // request from a satellite window
   private RpcRequest sendRequest(String sourceWindow,
                                  String scope, 
                                  String method, 
                                  JSONArray params,
                                  boolean redactLog,
                                  final RpcResponseHandler responseHandler,
                                  final RetryHandler retryHandler)
   {      
      // ensure we are listening for events. note that we do this here
      // because we are no longer so aggressive about retrying on failed
      // get_events calls. therefore, if we retry and fail a few times
      // we may need to restart event listening.
      ensureListeningForEvents();

      // create request
      String rserverURL = getApplicationURL(scope) + "/" + method;
      RpcRequest rpcRequest = new RpcRequest(rserverURL,
                                             method,
                                             params,
                                             null,
                                             redactLog,
                                             sourceWindow,
                                             clientId_,
                                             clientVersion_);

      // send the request
      rpcRequest.send(new RpcRequestCallback() {
         public void onError(RpcRequest request, RpcError error)
         {
            // ignore errors if we are disconnected
            if ( disconnected_)           
               return;
            
            // if we have a retry handler then see if we can resolve the
            // error and then retry
            if ( resolveRpcErrorAndRetry(error, retryHandler) )
               return ;

            // first crack goes to globally registered rpc error handlers
            if (!handleRpcErrorInternally(error))
            {
               // no global handlers processed it, send on to caller
               responseHandler.onResponseReceived(RpcResponse.create(error));
            }
         }

         public void onResponseReceived(final RpcRequest request,
                                        RpcResponse response)
         {
            // ignore response if we are disconnected
            //   - handler was cancelled
            if (disconnected_) 
                 return;
                   
            // check for error
            if (response.getError() != null)
            {
               // ERROR: explicit error returned by server
               RpcError error = response.getError();

               // if we have a retry handler then see if we can resolve the
               // error and then retry
               if ( resolveRpcErrorAndRetry(error, retryHandler) )
                  return ;

               // give first crack to internal handlers, then forward to caller
               if (!handleRpcErrorInternally(error))
                  responseHandler.onResponseReceived(response);
            }
            else if (response.getAsyncHandle() != null)
            {
               serverEventListener_.registerAsyncHandle(
                     response.getAsyncHandle(),
                     request,
                     this);
            }
            // no error, process the result
            else
            {
               // no error, forward to caller
               responseHandler.onResponseReceived(response);
               
               // always ensure that the event source receives events unless 
               // the server specifically flags us that no events are likely
               // to be pending (e.g. an rpc call where no events were added
               // to the queue by the call)
               if (eventsPending(response))
                  serverEventListener_.ensureEvents();
            }
         }
      });

      // return the request
      return rpcRequest;
   }
   
   private void ensureListeningForEvents()
   {
      // if we are in a mode where we are listening for events (running
      // as the main workbench) then ensure we are listening
      
      // we need the listeningForEvents_ flag because we don't want to cause
      // events to flow prior to the workbench being instantiated and fully 
      // initialized. since this method can be called at any time we need to
      // protect ourselves against this "pre-workbench initialization" state
      
      // the retries are there to work around the fact that when we execute a
      // network request which causes us to resume from a suspended session
      // the first query for events often returns ServiceUnavailable because 
      // the process isn't alive yet. by retrying we make certain that if
      // the first attempts to listen fail we eventually get synced up
      
      if (listeningForEvents_)
         serverEventListener_.ensureListening(10);
   }

   private boolean eventsPending(RpcResponse response)
   {
      String eventsPending = response.getField("ep");
      if (eventsPending == null)
         return true ; // default to true for json-rpc compactness
      else
         return Boolean.parseBoolean(eventsPending);
   }

   private boolean resolveRpcErrorAndRetry(final RpcError error,
                                           final RetryHandler retryHandler)
   {
      // won't even attempt resolve if we don't have a retryHandler
      if (retryHandler == null)
         return false;

      // can attempt to resolve UNAUTHORIZED by updating credentials
      if (error.getCode() == RpcError.UNAUTHORIZED)
      {
         // check credentials
         serverAuth_.updateCredentials(new ServerRequestCallback<Integer>() {

            @Override
            public void onResponseReceived(Integer response)
            {
               // allow retry on success, otherwise handle unauthorized error
               if (response.intValue() ==
                                 RemoteServerAuth.CREDENTIALS_UPDATE_SUCCESS)
               {
                  retryHandler.onRetry();
               }
               else
               {
                  handleUnauthorizedError();
               }
            }

            @Override
            public void onError(ServerError serverError)
            {
               // log the auth sequence error
               Debug.logError(serverError);

               // unable to resolve unauthorized error through a
               // credentials check -- treat as unauthorized
               handleUnauthorizedError();
            }
         });

         // attempting to resolve
         return true;
      }
      else
      {
         return false;
      }
   }

   private boolean handleRpcErrorInternally(RpcError error)
   {
      if (error.getCode() == RpcError.UNAUTHORIZED)
      {
         handleUnauthorizedError();
         return true;
      }
      else if (error.getCode() == RpcError.INVALID_CLIENT_ID)
      {
         // disconnect
         disconnect();

         // fire event
         ClientDisconnectedEvent event = new ClientDisconnectedEvent();
         eventBus_.fireEvent(event);

         // handled
         return true;
      }
      else if (error.getCode() == RpcError.INVALID_CLIENT_VERSION)
      {
         // disconnect
         disconnect();

         // fire event
         InvalidClientVersionEvent event = new InvalidClientVersionEvent();
         eventBus_.fireEvent(event);

         // handled
         return true;
      }
      else if (error.getCode() == RpcError.SERVER_OFFLINE)
      {
         // disconnect
         disconnect();

         // fire event
         ServerOfflineEvent event = new ServerOfflineEvent();
         eventBus_.fireEvent(event);

         // handled
         return true;

      }
      else
      {
         return false;
      }
   }

   private void disconnect()
   {
      disconnected_ = true;
      serverEventListener_.stop();
   }
   
   // the following sequence of calls enables marsahlling of remote server
   // requests from satellite windows back into the main workbench window
   
   // this code sets up the sendRemoteServerRequest global callback within
   // the main workbench
   private native void registerSatelliteCallback() /*-{
      var server = this;     
      $wnd.sendRemoteServerRequest = $entry(
         function(sourceWindow, scope, method, params, redactLog, responseCallback) {
            server.@org.rstudio.studio.client.server.remote.RemoteServer::sendRemoteServerRequest(Lcom/google/gwt/core/client/JavaScriptObject;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLcom/google/gwt/core/client/JavaScriptObject;)(sourceWindow, scope, method, params, redactLog, responseCallback);
         }
      ); 
   }-*/;
   
   // this code runs in the main workbench and implements the server request
   // and then calls back the satellite on the provided js responseCallback
   private void sendRemoteServerRequest(final JavaScriptObject sourceWindow,
                                        final String scope,
                                        final String method,
                                        final String params,
                                        final boolean redactLog,
                                        final JavaScriptObject responseCallback)
   {  
      // get the WindowEx from the sourceWindow
      final WindowEx srcWnd = sourceWindow.<WindowEx>cast();
      
      // get the json array from the string
      final JSONArray jsonParams = JSONParser.parseStrict(params).isArray();
           
      // setup an rpc response handler that proxies back to the js object
      class ResponseHandler extends RpcResponseHandler
      {
         @Override
         public void onResponseReceived(RpcResponse response)
         {
            if (!srcWnd.isClosed())
               performCallback(responseCallback, response);
         }
         
         public void onError(RpcError error)
         {
            RpcResponse errorResponse = RpcResponse.create(error);
            if (!srcWnd.isClosed())
               performCallback(responseCallback, errorResponse);
         }
         
         private native void performCallback(JavaScriptObject responseCallback,
                                             RpcResponse response) /*-{
            responseCallback.onResponse(response);
         }-*/;
      };
      final ResponseHandler responseHandler = new ResponseHandler();
      
      // setup a retry handler which will call back the second time with
      // the same args (but no retryHandler, ensurin at most 1 retry)
      RetryHandler retryHandler = new RetryHandler() {
        
         public void onRetry()
         {
            // retry one time (passing null as last param ensures there
            // is no retry handler installed)
            sendRequest(getSourceWindowName(sourceWindow),
                        scope, 
                        method, 
                        jsonParams, 
                        redactLog, 
                        responseHandler, 
                        null);
         }   

         public void onError(RpcError error)
         {
            // propagate error which caused the retry to the caller
            responseHandler.onError(error);
         }
      };
      
      // submit request (retry same request up to one time)
      sendRequest(getSourceWindowName(sourceWindow),
                  scope, 
                  method, 
                  jsonParams, 
                  redactLog, 
                  responseHandler, 
                  retryHandler);
   }
   
   private native String getSourceWindowName(JavaScriptObject sourceWindow) /*-{
      return sourceWindow.RStudioSatelliteName;
   }-*/;
   
   // call made from satellite -- this delegates to a native method which
   // sets up a javascript callback and then calls the main workbench
   private <T> void sendRequestViaMainWorkbench(
                               String scope,
                               String method,
                               JSONArray params,
                               boolean redactLog,
                               final ServerRequestCallback<T> requestCallback)
   {
      sendRequestViaMainWorkbench(
            scope, 
            method, 
            params.toString(),
            redactLog, 
            new RpcResponseHandler() {
               @Override
               public void onResponseReceived(RpcResponse response)
               {
                  if (response.getError() != null)
                  {
                     RpcError error = response.getError();
                     requestCallback.onError(new RemoteServerError(error));
                  }
                  else
                  {
                     T result = response.<T> getResult();
                     requestCallback.onResponseReceived(result);
                  }
                  
               }
      });
   }

   // call from satellite to sendRemoteServerRequest method made available
   // by main workbench
   private native void sendRequestViaMainWorkbench(
                                    String scope,
                                    String method,
                                    String params,
                                    boolean redactLog,
                                    RpcResponseHandler handler) /*-{
      
      var responseCallback = new Object();
      responseCallback.onResponse = $entry(function(response) {
        handler.@org.rstudio.core.client.jsonrpc.RpcResponseHandler::onResponseReceived(Lorg/rstudio/core/client/jsonrpc/RpcResponse;)(response);
      });

      $wnd.opener.sendRemoteServerRequest($wnd,
                                          scope, 
                                          method, 
                                          params, 
                                          redactLog,
                                          responseCallback);
   }-*/;

   @Override
   public void svnAdd(ArrayList<String> paths,
                      ServerRequestCallback<ProcessResult> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, toJSONStringArray(paths));
      sendRequest(RPC_SCOPE, SVN_ADD, params, requestCallback);
   }

   @Override
   public void svnDelete(ArrayList<String> paths,
                         ServerRequestCallback<ProcessResult> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, toJSONStringArray(paths));
      sendRequest(RPC_SCOPE, SVN_DELETE, params, requestCallback);
   }

   @Override
   public void svnRevert(ArrayList<String> paths,
                         ServerRequestCallback<ProcessResult> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, toJSONStringArray(paths));
      sendRequest(RPC_SCOPE, SVN_REVERT, params, requestCallback);
   }

   @Override
   public void svnStatus(ServerRequestCallback<JsArray<StatusAndPathInfo>> requestCallback)
   {
      sendRequest(RPC_SCOPE, SVN_STATUS, requestCallback);
   }

   @Override
   public void svnUpdate(ServerRequestCallback<ConsoleProcess> requestCallback)
   {
      sendRequest(RPC_SCOPE, SVN_UPDATE,
                  new ConsoleProcessCallbackAdapter(requestCallback));
   }

   @Override
   public void svnCommit(ArrayList<String> paths,
                         String message,
                         ServerRequestCallback<ConsoleProcess> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, toJSONStringArray(paths));
      params.set(1, new JSONString(message));

      sendRequest(RPC_SCOPE, SVN_COMMIT, params,
                  new ConsoleProcessCallbackAdapter(requestCallback));
   }

   @Override
   public void svnDiffFile(String path,
                           Integer contextLines,
                           boolean noSizeWarning,
                           ServerRequestCallback<String> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(path));
      params.set(1, new JSONNumber(contextLines));
      params.set(2, JSONBoolean.getInstance(noSizeWarning));
      sendRequest(RPC_SCOPE, SVN_DIFF_FILE, params, requestCallback);
   }

   @Override
   public void svnApplyPatch(String path,
                             String patch,
                             ServerRequestCallback<Void> requestCallback)
   {
      JSONArray params = new JSONArray();
      params.set(0, new JSONString(path));
      params.set(1, new JSONString(patch));
      sendRequest(RPC_SCOPE, SVN_APPLY_PATCH, params, requestCallback);
   }

   private String clientId_;
   private double clientVersion_ = 0;
   private boolean listeningForEvents_;
   private boolean disconnected_;

   private final RemoteServerAuth serverAuth_;
   private final RemoteServerEventListener serverEventListener_ ;

   private final Provider<ConsoleProcessFactory> pConsoleProcessFactory_;

   private final Session session_;
   private final EventBus eventBus_;
   private final Satellite satellite_;

   // url scopes
   private static final String RPC_SCOPE = "rpc";
   private static final String FILES_SCOPE = "files";
   private static final String EVENTS_SCOPE = "events";
   private static final String UPLOAD_SCOPE = "upload";
   private static final String EXPORT_SCOPE = "export";
   private static final String GRAPHICS_SCOPE = "graphics";
   private static final String SOURCE_SCOPE = "source";
   private static final String LOG_SCOPE = "log";
   private static final String FILE_SHOW = "file_show";

   // session methods
   private static final String CLIENT_INIT = "client_init";
   private static final String ACCEPT_AGREEMENT = "accept_agreement";
   private static final String SUSPEND_SESSION = "suspend_session";
   private static final String HANDLE_UNSAVED_CHANGES_COMPLETED = "handle_unsaved_changes_completed";
   private static final String QUIT_SESSION = "quit_session";

   private static final String SET_WORKBENCH_METRICS = "set_workbench_metrics";
   private static final String SET_PREFS = "set_prefs";
   private static final String SET_UI_PREFS = "set_ui_prefs";
   private static final String GET_R_PREFS = "get_r_prefs";
   private static final String SET_CLIENT_STATE = "set_client_state";
   private static final String USER_PROMPT_COMPLETED = "user_prompt_completed";
   private static final String SEARCH_CODE = "search_code";
   private static final String GET_SEARCH_PATH_FUNCTION_DEFINITION = "get_search_path_function_definition";
   private static final String GET_METHOD_DEFINITION = "get_method_definition";
   private static final String GET_FUNCTION_DEFINITION = "get_function_definition";
   private static final String FIND_FUNCTION_IN_SEARCH_PATH = "find_function_in_search_path";

   private static final String CONSOLE_INPUT = "console_input";
   private static final String RESET_CONSOLE_ACTIONS = "reset_console_actions";
   private static final String INTERRUPT = "interrupt";
   private static final String ABORT = "abort";
   private static final String HTTP_LOG = "http_log";
   private static final String GET_COMPLETIONS = "get_completions";
   private static final String GET_HELP_AT_CURSOR = "get_help_at_cursor";

   private static final String PROCESS_INIT = "process_init";
   private static final String PROCESS_START = "process_start";
   private static final String PROCESS_INTERRUPT = "process_interrupt";
   private static final String PROCESS_REAP = "process_reap";
   private static final String PROCESS_WRITE_STDIN = "process_write_stdin";

   private static final String LIST_OBJECTS = "list_objects";
   private static final String REMOVE_ALL_OBJECTS = "remove_all_objects";
   private static final String SET_OBJECT_VALUE = "set_object_value";
   private static final String GET_OBJECT_VALUE = "get_object_value";
   private static final String LIST_GOOGLE_SPREADSHEETS = "list_google_spreadsheets";
   private static final String IMPORT_GOOGLE_SPREADSHEET = "import_google_spreadsheet";
   private static final String DOWNLOAD_DATA_FILE = "download_data_file";
   private static final String GET_DATA_PREVIEW = "get_data_preview";
   private static final String GET_OUTPUT_PREVIEW = "get_output_preview";

   private static final String EDIT_COMPLETED = "edit_completed";
   private static final String CHOOSE_FILE_COMPLETED = "choose_file_completed";

   private static final String LIST_PACKAGES = "list_packages";
   private static final String AVAILABLE_PACKAGES = "available_packages";
   private static final String CHECK_FOR_PACKAGE_UPDATES = "check_for_package_updates";
   private static final String INIT_DEFAULT_USER_LIBRARY = "init_default_user_library";
   private static final String GET_PACKAGE_INSTALL_CONTEXT = "get_package_install_context";
   private static final String IS_PACKAGE_LOADED = "is_package_loaded";
   private static final String SET_CRAN_MIRROR = "set_cran_mirror";
   private static final String GET_CRAN_MIRRORS = "get_cran_mirrors";

   private static final String GET_HELP = "get_help";
   private static final String SHOW_HELP_TOPIC = "show_help_topic" ;
   private static final String SEARCH = "search" ;

   private static final String STAT = "stat";
   private static final String LIST_FILES = "list_files";
   private static final String LIST_ALL_FILES = "list_all_files";
   private static final String CREATE_FOLDER = "create_folder";
   private static final String DELETE_FILES = "delete_files";
   private static final String COPY_FILE = "copy_file";
   private static final String MOVE_FILES = "move_files";
   private static final String RENAME_FILE = "rename_file";
   private static final String COMPLETE_UPLOAD = "complete_upload";

   private static final String NEXT_PLOT = "next_plot";
   private static final String PREVIOUS_PLOT = "previous_plot";
   private static final String REMOVE_PLOT = "remove_plot";
   private static final String CLEAR_PLOTS = "clear_plots";
   private static final String REFRESH_PLOT = "refresh_plot";
   private static final String SAVE_PLOT_AS = "save_plot_as";
   private static final String SAVE_PLOT_AS_PDF = "save_plot_as_pdf";
   private static final String COPY_PLOT_TO_CLIPBOARD_METAFILE = "copy_plot_to_clipboard_metafile";
   private static final String GET_UNIQUE_SAVE_PLOT_STEM = "get_unique_save_plot_stem";
   private static final String GET_SAVE_PLOT_CONTEXT = "get_save_plot_context";
   private static final String LOCATOR_COMPLETED = "locator_completed";
   private static final String SET_MANIPULATOR_VALUES = "set_manipulator_values";
   private static final String MANIPULATOR_PLOT_CLICKED = "manipulator_plot_clicked";

   private static final String CREATE_PROJECT = "create_project";
   private static final String READ_PROJECT_OPTIONS = "read_project_options";
   private static final String WRITE_PROJECT_OPTIONS = "write_project_options";
   private static final String WRITE_PROJECT_VCS_OPTIONS = "write_project_vcs_options";
   
   private static final String NEW_DOCUMENT = "new_document";
   private static final String OPEN_DOCUMENT = "open_document";
   private static final String SAVE_DOCUMENT = "save_document";
   private static final String SAVE_DOCUMENT_DIFF = "save_document_diff";
   private static final String CHECK_FOR_EXTERNAL_EDIT = "check_for_external_edit";
   private static final String IGNORE_EXTERNAL_EDIT = "ignore_external_edit";
   private static final String CLOSE_DOCUMENT = "close_document";
   private static final String CLOSE_ALL_DOCUMENTS = "close_all_documents";
   private static final String SET_SOURCE_DOCUMENT_ON_SAVE = "set_source_document_on_save";
   private static final String SAVE_ACTIVE_DOCUMENT = "save_active_document";
   private static final String MODIFY_DOCUMENT_PROPERTIES = "modify_document_properties";
   private static final String REVERT_DOCUMENT = "revert_document";
   private static final String REOPEN_WITH_ENCODING = "reopen_with_encoding";
   private static final String REMOVE_CONTENT_URL = "remove_content_url";
   private static final String DETECT_FREE_VARS = "detect_free_vars";
   private static final String ICONVLIST = "iconvlist";
   private static final String PUBLISH_PDF = "publish_pdf";
   private static final String IS_TEX_INSTALLED = "is_tex_installed";

   private static final String GET_RECENT_HISTORY = "get_recent_history";
   private static final String GET_HISTORY_ITEMS = "get_history_items";
   private static final String REMOVE_HISTORY_ITEMS = "remove_history_items";
   private static final String CLEAR_HISTORY = "clear_history";
   private static final String GET_HISTORY_ARCHIVE_ITEMS = "get_history_archive_items";
   private static final String SEARCH_HISTORY_ARCHIVE = "search_history_archive";
   private static final String SEARCH_HISTORY_ARCHIVE_BY_PREFIX = "search_history_archive_by_prefix";

   private static final String GIT_ADD = "git_add";
   private static final String GIT_REMOVE = "git_remove";
   private static final String GIT_DISCARD = "git_discard";
   private static final String GIT_REVERT = "git_revert";
   private static final String GIT_STAGE = "git_stage";
   private static final String GIT_UNSTAGE = "git_unstage";
   private static final String GIT_ALL_STATUS = "git_all_status";
   private static final String GIT_FULL_STATUS = "git_full_status";
   private static final String GIT_LIST_BRANCHES = "git_list_branches";
   private static final String GIT_CHECKOUT = "git_checkout";
   private static final String GIT_COMMIT = "git_commit";
   private static final String GIT_CLONE = "git_clone";
   private static final String GIT_PUSH = "git_push";
   private static final String GIT_PULL = "git_pull";
   private static final String ASKPASS_COMPLETED = "askpass_completed";
   private static final String GIT_SSH_PUBLIC_KEY = "git_ssh_public_key";
   private static final String GIT_HAS_REPO = "git_has_repo";
   private static final String GIT_INIT_REPO = "git_init_repo";
   private static final String GIT_DIFF_FILE = "git_diff_file";
   private static final String GIT_APPLY_PATCH = "git_apply_patch";
   private static final String GIT_HISTORY_COUNT = "git_history_count";
   private static final String GIT_HISTORY = "git_history";
   private static final String GIT_EXECUTE_COMMAND = "git_execute_command";
   private static final String GIT_SHOW = "git_show";

   private static final String SVN_ADD = "svn_add";
   private static final String SVN_DELETE = "svn_delete";
   private static final String SVN_REVERT = "svn_revert";
   private static final String SVN_STATUS = "svn_status";
   private static final String SVN_UPDATE = "svn_update";
   private static final String SVN_COMMIT = "svn_commit";
   private static final String SVN_DIFF_FILE = "svn_diff_file";
   private static final String SVN_APPLY_PATCH = "svn_apply_patch";

   private static final String GET_PUBLIC_KEY = "get_public_key";
   
   private static final String LIST_GET = "list_get";
   private static final String LIST_PREPEND_ITEM = "list_prepend_item";
   private static final String LIST_APPEND_ITEM = "list_append_item";
   private static final String LIST_REMOVE_ITEM = "list_remove_item";
   private static final String LIST_CLEAR = "list_clear";
   
   private static final String START_POSIX_SHELL = "start_posix_shell";
   private static final String INTERRUPT_POSIX_SHELL = "interrupt_posix_shell";
   private static final String SEND_INPUT_TO_POSIX_SHELL = "send_input_to_posix_shell";
   private static final String TERMINATE_POSIX_SHELL = "terminate_posix_shell";
   
   private static final String LOG = "log";


}
