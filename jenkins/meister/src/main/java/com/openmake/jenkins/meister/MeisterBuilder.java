package com.openmake.jenkins.meister;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.console.HyperlinkNote;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;

import javax.servlet.ServletException;

import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.openmake.generated.TaskList;
import com.openmake.integration.OMClient;
import com.openmake.integration.ServerInterfaceException;

/**
 * Sample {@link Builder}.
 * 
 * <p>
 * When the user configures the project and enables this builder, {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked and a new {@link MeisterBuilder} is created. The created instance is persisted to the project configuration XML by using XStream, so this allows you to use instance
 * fields (like {@link #name}) to remember the configuration.
 * 
 * <p>
 * When a build is performed, the {@link #perform} method will be invoked.
 * 
 * @author Kohsuke Kawaguchi
 */
public class MeisterBuilder extends Builder implements SimpleBuildStep
{
 private final String name;
 private final String kbserver;
 OMClient omClient = null;
 private String userID;

 // Fields in config.jelly must match the parameter names in the
 // "DataBoundConstructor"
 @DataBoundConstructor
 public MeisterBuilder(String name, String kbserver)
 {
  this.name = name;
  this.kbserver = kbserver;
 }

 /**
  * We'll use this from the <tt>config.jelly</tt>.
  */
 public String getName()
 {
  return name;
 }

 public String getKbserver()
 {
  return kbserver;
 }

 @Override
 public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener)
 {
  initOMClient(kbserver);

  TaskList buildJob = null;
  boolean isPublic = true;

  // Attempt to get a public Build Job first
  try
  {
   buildJob = omClient.getBuildJob(isPublic, name);

   if (buildJob == null)
   {
    isPublic = false;
    // If there's no public Build Job, attempt to get a Private
    buildJob = omClient.getBuildJob(isPublic, name);
   }

   if (buildJob == null)
   {
    listener.getLogger().println("Build Job, " + name + ", not found.");
    build.setResult(Result.FAILURE);
    return; 
   }
   
   final String[] logUrls = omClient.executeBuildJob(buildJob);
   final String fullJobName = buildJob.getName();
   if (logUrls != null && logUrls.length > 0)
   {
    listener.getLogger().println("\nBuild logs for: " + fullJobName);

    boolean hadFail = false;
    // boolean logPassed;
    for (int i = 0; i < logUrls.length; i++)
    {
     String[] urlparts = logUrls[i].split("\\?");
     String logurl = urlparts[0];
     logurl = logurl.replace("OMDisplayLog", "logs");
     String params = urlparts[1];
     String[] parts = params.split("&");
     String machine = parts[1].substring("Machine=".length());
     
     listener.getLogger().println(HyperlinkTargetBlankNote.encodeTo(logUrls[i], "Real-time Log Output for " + machine));
    }
    
    for (int i = 0; i < logUrls.length; i++)
    {
     boolean logPassed = waitOnLog(listener, logUrls[i], fullJobName);
     //
     if (!logPassed)
      hadFail = true;
     // }
     // else
     // just print the log URL
     String[] urlparts = logUrls[i].split("\\?");
     String logurl = urlparts[0];
     logurl = logurl.replace("OMDisplayLog", "logs");
     String params = urlparts[1];
     String[] parts = params.split("&");
     String jobName = parts[0].substring("BuildJobName=".length());
     String datetime = parts[2].substring("DateTime=".length());
     String username = parts[3].substring("UserName=".length());
     String publicBuildJob = parts[4].substring("PublicBuildJob=".length());
     
     if (publicBuildJob.equalsIgnoreCase("true"))
      logurl += "/public/" + jobName + "-" + datetime + "/Log%20Detail/index.html";
     else
      logurl += "/" + username + "/" + jobName + "-" + datetime + "/Log%20Detail/index.html";

     listener.getLogger().println(IFrameNote.encodeTo(logurl,"Log Output"));

    }

    if (hadFail)
    {
     build.setResult(Result.FAILURE);
     listener.getLogger().println("Build Job, " + fullJobName + ", failed.");
    }
   }
  }
  catch (ServerInterfaceException e)
  {
   e.printStackTrace();
  }
  build.setResult(Result.SUCCESS);
 }

 private boolean waitOnLog(TaskListener listener,String logUrl, String jobName)
 {
  boolean success = true;

  System.out.println("LogURL=" + logUrl);
  
  do
  {
   success = true;

   try
   {
    long numMillisecondsToSleep = 10000;
    Thread.sleep(numMillisecondsToSleep);
   }
   catch (InterruptedException e)
   {
    listener.getLogger().println("Caught InterruptedException while waiting on Log: " + e.getMessage());
   }
   try
   {
    URL url = new URL(logUrl);
    BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()));

    String inputLine;
    String frameUrl = null;

    boolean gotFrame = false;
    while (frameUrl == null)
    {
     // start by getting the frame URL
     while (frameUrl == null && (inputLine = in.readLine()) != null)
     {
      // HTML will look like
      // <meta http-equiv="refresh" content="0;
      // URL=http://192.168.2.103:58080/openmake/logs/public/Test%20Deploy---My%20Log-My%20Computer-2008-02-20%2011_01_01/Test%20Deploy---My%20Log-My%20Computer-2008-02-20%2011_01_01-Frame.html">
      if (inputLine.indexOf("Frame.html") >= 0)
      {
       // Main.PrintMsg("Found frame URL: " + inputLine);
       int idx = inputLine.indexOf("URL=");
       if (idx < 0)
       {
        // Main.PrintMsg("Unable to determine frame URL: " + inputLine);
        return false;
       }

       frameUrl = inputLine.substring(idx + 4);
       // strip off Frame.html">
       frameUrl = frameUrl.substring(0, frameUrl.length() - 12);
       // add Summary.html
       frameUrl = frameUrl + "Summary.html";
      }
     }

     try
     {
      long numMillisecondsToSleep = 3000;
      Thread.sleep(numMillisecondsToSleep);
     }
     catch (InterruptedException e)
     {
      listener.getLogger().println("Caught InterruptedException while waiting on Log: " + e.getMessage());
     }

     // reset the input stream for the next while loop
     in.close();
     url = new URL(logUrl);
     in = new BufferedReader(new InputStreamReader(url.openStream()));
    }

    // open the frame URL and read from it
    url = new URL(frameUrl);
    BufferedReader frameIn = new BufferedReader(new InputStreamReader(url.openStream()));

    boolean hadError = false;
    boolean done = false;
    while (!done)
    {
     while ((inputLine = frameIn.readLine()) != null)
     {
      // HTML line for complete will look like:
      // Build Job complete for Test Deploy---My Log
      // HTML line for error will contain: "redball.gif"
      // HTML line for success will contain: "grnball.gif"

      if (inputLine.indexOf("redball.gif") >= 0)
       hadError = true;
      if (inputLine.indexOf("Build Job complete ") >= 0)
      {
       // Main.PrintMsg(jobName + "Complete:\t\t" + inputLine);
       // if(!hadError)
       if (inputLine.indexOf("grnball.gif") >= 0)
       {
//        Main.PrintMsg("\tCommand Line Executed Workflow \'" + jobName + "\' completed successfully.");
//        Main.PrintMsg("\t<A HREF=\"" + logUrl + "\">View Log For " + jobName + "</A>");
        done = true;
        return true;
       }
       else
       {
//        Main.PrintMsg("\tCommand Line Executed Workflow \'" + jobName + "\' completed unsuccessfully.");
//        Main.PrintMsg("\t<A HREF=\"" + logUrl + "\">View Log For " + jobName + "</A>");
        done = true;
        return false;
       }
      }
     }
     try
     {
      // Main.PrintMsg("Build Job \'" + jobName + "\' not completed.
      // Waiting...");
      long numMillisecondsToSleep = 2000;
      Thread.sleep(numMillisecondsToSleep);
     }
     catch (InterruptedException e)
     {
      listener.getLogger().println("Caught InterruptedException while waiting on Log: " + e.getMessage());
     }

     // reset the input stream
     frameIn.close();
     url = new URL(frameUrl);
     frameIn = new BufferedReader(new InputStreamReader(url.openStream()));
    }
    in.close();
   }
   catch (IOException ioe)
   {
    listener.getLogger().println("Caught exception reading log URL: " + logUrl);
    listener.getLogger().println(ioe.getMessage());
    success = false;
   }
  }
  while (!success);
  return false;
 }

 public void initOMClient(String server)
 {
  // In order to execute build jobs, we need to make the server connection
  // through OMClient
  setupOMClient(server);
  // connect to the KB Server
  connectServer();
  // set the User
  userID = System.getProperty("user.name");
  setUser(userID);
 }

 /**
  * Method that sets up OMClient. OMClient is used to handle some of the functionality required by Merant.
  * 
  */
 protected void setupOMClient(String HTMLAddress)
 {
  // construct OMClient and set up the required variables
  if (omClient == null)
  {
   omClient = new OMClient();
  }
  // ADG - 01.22.07 - Case 7491
  // set OMClient.HTMLServer
  // omFileManager = omClient.getFileManager();
  // String HTMLAddress = omFileManager.getEnv("OPENMAKE_SERVER");
  omClient.setHTMLServer(HTMLAddress);
  // System.out.println("Set OMClient.HTMLServer to: " + HTMLAddress);
  // set OMClient.KBAddress
  int i = HTMLAddress.lastIndexOf('/');
  String KBAddress = HTMLAddress.substring(0, i) + "/soap/servlet/openmakeserver";
  omClient.setKBAddress(KBAddress);
 }

 /**
  * Method that connects to the Knowledge Base Server using the OMClient API.
  * 
  */
 protected boolean connectServer()
 {
  try
  {
   omClient.connect();
  }
  catch (ServerInterfaceException sie)
  {
   return false;
  }
  return true;
 }

 /**
  * Method that sets the User ID using the OMClient API.
  * 
  * @param userID
  *         The User ID as a String.
  */
 protected boolean setUser(String userID)
 {
  try
  {
   omClient.setUser(userID);
  }
  catch (ServerInterfaceException sie)
  {
   return false;
  }
  return true;
 }

 // Overridden for better type safety.
 // If your plugin doesn't really define any property on Descriptor,
 // you don't have to do this.
 @Override
 public DescriptorImpl getDescriptor()
 {
  return (DescriptorImpl) super.getDescriptor();
 }

 /**
  * Descriptor for {@link MeisterBuilder}. Used as a singleton. The class is marked as public so that it can be accessed from views.
  * 
  * <p>
  * See <tt>src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly</tt> for the actual HTML fragment for the configuration screen.
  */
 @Extension
 // This indicates to Jenkins that this is an implementation of an extension
 // point.
 public static final class DescriptorImpl extends BuildStepDescriptor<Builder>
 {
  /**
   * In order to load the persisted global configuration, you have to call load() in the constructor.
   */
  public DescriptorImpl()
  {
   load();
  }

  /**
   * Performs on-the-fly validation of the form field 'name'.
   * 
   * @param value
   *         This parameter receives the value that the user has typed.
   * @return Indicates the outcome of the validation. This is sent to the browser.
   *         <p>
   *         Note that returning {@link FormValidation#error(String)} does not prevent the form from being saved. It just means that a message will be displayed to the user.
   */
  public FormValidation doCheckName(@QueryParameter String value) throws IOException, ServletException
  {
   if (value.length() == 0)
    return FormValidation.error("Please set a Workflow name");
   return FormValidation.ok();
  }

  /**
   * Performs on-the-fly validation of the form field 'kbserver'.
   * 
   * @param value
   *         This parameter receives the value that the user has typed.
   * @return Indicates the outcome of the validation. This is sent to the browser.
   *         <p>
   *         Note that returning {@link FormValidation#error(String)} does not prevent the form from being saved. It just means that a message will be displayed to the user.
   */
  public FormValidation doCheckKbserver(@QueryParameter String value) throws IOException, ServletException
  {
   if (value.length() == 0)
    return FormValidation.error("Please set the KB Server URL");
   return FormValidation.ok();
  }

  @Override
  public boolean configure(StaplerRequest staplerRequest, JSONObject json) throws FormException
  {
   save();
   return true; // indicate that everything is good so far
  }

  public boolean isApplicable(Class<? extends AbstractProject> aClass)
  {
   // Indicates that this builder can be used with all kinds of project
   // types
   return true;
  }

  /**
   * This human readable name is used in the configuration screen.
   */
  public String getDisplayName()
  {
   return "Execute Meister Workflow on Server Pool";
  }

 }
}
