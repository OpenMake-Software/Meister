use File::Copy;
use cwd;
use Openmake::File;
$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Visual\040Basic\0407\040Compiler.sc,v 1.3 2005/05/16 16:16:36 jim Exp $';
#-- Clean up $ScriptVersion so that it prints useful information
if ($ScriptVersion =~ /^\s*\$Header:\s*(\S+),v\s+(\S+)\s+(\S+)\s+(\S+)/ )
{
 my $path = $1;
 my $version = $2;
 my $date = $3;
 my $time = $4;

 #-- massage path
 $path =~ s/\\040/ /g;
 my @t = split /\//, $path;
 my $file = pop @t;
 my $module = $t[2];
 
 $ScriptVersion = "$file : $module, v$version, $date $time";
}

#########################################
#-- Load Openmake Variables from Data File
#   Uncomment Openmake objects that need to be loaded

@PerlData = $AllDeps->load("AllDeps", @PerlData );
@PerlData = $RelDeps->load("RelDeps", @PerlData );
#  @PerlData = $NewerDeps->load("NewerDeps", @PerlData );
@PerlData = $TargetDeps->load("TargetDeps", @PerlData );
#  @PerlData = $TargetRelDeps->load("TargetRelDeps", @PerlData );
#  @PerlData = $TargetSrcDeps->load("TargetSrcDeps", @PerlData );

####### Setting Compiler Choice and Search Behavior #######  

@CompilersPassedInFlags = ("devenv");
$DefaultCompiler  = "devenv";

($Compiler,$Flags) = get_compiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);

$IntDirName = $IntDir->get;

$CFG = "Release" if not defined $CFG;

$cwd = cwd();
$cwd =~ s/\//\\/g;

# Make sure that the build isn't being attempted at the SOLUTION level
$sln = $TargetDeps->getExt(qw(.SLN));

#-- If there is a Solution file, exit the script.

# copy local
@localres = CopyExcludeLocal($AllDeps,$RelDeps,$cwd, qw(.dll .exe));

if($sln ne "")
{
 # Exit the script. The user needs to use a SOLUTION Build Type to build at the Solution level.
 $RC = 1;
 $StepError = ".SLN files have been defined as Dependencies. Please use a \.NET Solution Build Type to build at the Solution level.";
 push(@CompilerOut,$StepError);
 omlogger("Final",$StepDescription,"ERROR:","$StepError.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
 ExitScript($RC,@DeleteFileList); 
} 
else
{
 # get the .VBPROJ or .CSPROJ file from the $TargetDeps
 @Projects = unique($TargetDeps->getExt(qw(.VBPROJ .CSPROJ)));
 $tmp = shift @Projects;
 $tmpProj = new Openmake::File($tmp);
 # create temporary proj file
 CreateTempProj($tmpProj); 
}

# validate the any .VDPROJ files included in the build
@VdProjects = unique($RelDeps->getExt(qw(.VDPROJ)));
if(@VdProjects != ())
{
 foreach $vdproj (@VdProjects)
 {
  # create temporary proj files
  $vdprojObj = new Openmake::File($vdproj);
  ValidateVDProj($vdprojObj);
 }
}


#-- Run the Compile
#
#   For Project Builds, compile against the .VBPROJ or .CSPROJ file: $CompilerArguments = "$Outvbp /build $CFG /nologo 2>&1";

 $CompilerArguments = "$buildtarget /rebuild $CFG /nologo 2>&1";

#-- Before calling DEVENV, we have to delete all existing versions of the Targets (held in @TargetCleanup
#   in the Build Directory and its sub-directories. If we don't do this, older versions of the 
#   Targets could cause DEVENV to throw Version Errors.
foreach $delTarget ( @TargetCleanup )
{
 `del /S /Q $delTarget 2>nul`;
}

$Command = "$Compiler $CompilerArguments";
$StepDescription = "Visual Basic 7 Compiling project $Proj";
$RC = 0;
omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
@CompilerOut = `$Command`;

if ( $? != 0 ) 
{
 $RC = 1;
 $StepError = "$Compiler did not execute properly. ";
 push(@CompilerOut,$StepError);
 omlogger("Final",$StepDescription,"ERROR:","$StepError.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);

 # clean up the build directory
 CleanUp();

 ExitScript($RC,@DeleteFileList);
}
else
{               
 $RC = 0;
 $StepError = "$Compiler Completed successfully \n";
 push(@CompilerOut,$StepError);
        
 # clean up the build directory
 CleanUp();

 # push(@CompilerOut,@LogOut);
 push(@DeleteFileList,@dellist); 
 omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
 ExitScript($RC,@DeleteFileList);
}
##############################
sub CreateTempProj
##############################
{
 use File::stat;
 
 my $Proj = shift;
 $Log = $Target->getF . ".Log";

 #-- $Webproject needs to be global
 # $WebProject = 0;
 my $currentWebProject = 0;
 my $WebPath = '';
 my $WebNewPath = '';

 # get the Project Directory from the $Proj filename 
 if($Proj->get =~ /\\/)
 {
  #-- JAG - this has to be $Proj->get
  #@Temp = split(/\\/,$Proj);
  @Temp = split(/\\/,$Proj->get);
  pop(@Temp);
  $ProjectDir = join("\\",@Temp);
  if($ProjectDir ne "")
  {
   $ProjectDir = $ProjectDir."\\";
  }
 }
 
 # Read the Source project file. EXIT if we can't open it
 my $SrcProj = $Proj->get;
 unless ( open(SRC,"$SrcProj") ) 
 {
  $StepError = "Can't open source file: $SrcProj\n";
  $RC = 1;
  omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepError",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), push(@DeleteFileList,$Target->get), ExitScript("1",@DeleteFileList) if ($RC != 0);
  push(@DeleteFileList,$Log);
  push(@DeleteFileList,@dellist);
  ExitScript($RC,@DeleteFileList);
 }
 @SourceLines = <SRC>;
 close(SRC);

 # Creating random name for temporary project file
 # Returning temporaryfileHandle and name of temp project file

 $projExt = $Proj->getE;
 ($tmpfh, $Outproj) = tempfile('omXXXXX', SUFFIX => $projExt, UNLINK => 0);
 close($tmpfh);
 push(@dellist,$Outproj) unless $KeepScript =~ /YES/i;

 unless ( open(PROJ,">$Outproj") ) 
 {
  $StepError = "Can't write to file: $Outproj\n";
  $RC = 1;
  omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepError",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), push(@DeleteFileList,$Target->get), ExitScript("1",@DeleteFileList) if ($RC != 0);
  push(@DeleteFileList,$Log);
  push(@DeleteFileList,@dellist);
  ExitScript($RC,@DeleteFileList);
 }

 ######Process Source PROJ lines
 $FoundAssembly = 0;
 foreach $line (@SourceLines)
 {
  chomp($_);  # remove trailing carriage returns to be able to pattern match
  $line =~ s/^\s+//g; 
  
  #-- the closing tags in the project files are usually on there own line. however, sometimes they are on the end of the line. if the
  #   closing tag is on the end of the line, we need to remove it. then, when we write the line, add it back in
  if($line =~ /\/>$/ && !($line =~ /^\/>$/))
  {
   $line =~ s/\/>$//;
   $endingTag = '/>';
  }
  else
  {
   $endingTag = "";  
  }
  
  
  if ($line =~ /AssemblyName/ && $FoundAssembly == 0)
  {
   $FoundAssembly = 1;
   ($Key,$ProjAssembly) = split(/=/,$line);
   $ProjAssembly =~ s/^\s*//g;
   $ProjAssembly =~ s/\s*$//g;

   # set the Assembly name based on the Target's Name and see if it matches the $ProjAssembly
   $Assembly = $Target->getF;
   $Assembly = '"' . $Assembly . '"';

   if ($ProjAssembly eq $Assembly) #since they match, grab the Output Path from the Target Name
   {
    $TargetFile = $Target->getPFE;
    $PrePath = $Target->getP if ($TargetFile =~ /\\/);
    $PrePath .= "\\" if ($PrePath !~ /\\$/);  
    # flip the $AssemblyMatch flag
    $AssemblyMatch = 1;
    GenerateBillofMat($BillOfMaterialFile->get,$BillOfMaterialRpt,$TargetFile) if ( defined $BillOfMaterialFile);

    # set the $ProjTarget which gets used during the OUTPUT PATH handling
    $ProjTarget = $Target->getFE;
   }
   else # The Assembly name doesn't match the Target Name so we have to Exit 
   {
    # flip the $AssemblyMatch flag
    $AssemblyMatch = 0;
   }
        
   if (!$AssemblyMatch)
   {
    $StepError = "AssemblyName doesn't match Openmake Target: Inconsistent Target File\n";
    push(@CompilerOut,$StepError);
    $RC = 1;
    push(@DeleteFileList,$Log);
    push(@DeleteFileList,@dellist);
    omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepError",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), push(@DeleteFileList,$Target->get), ExitScript("1",@DeleteFileList) if ($RC != 0);
    ExitScript($RC,@DeleteFileList);   
   }
   else
   {
    print PROJ "$line $endingTag\n";
   }
  }
  elsif ($line =~ /OutputPath/)
  {
   if( $currentWebProject ) # don't do anything
   {
    print PROJ "$line $endingTag\n";
   }
   else
   {
    # Set the Output Path based on the $PrePath value
    $OPValue = ".";
    if($PrePath ne "\\" || $PrePath ne "")
    {
     $OPValue = "$cwd\\$PrePath";    
    }
    
    $OPValue = '"' . "$OPValue" . '"'; 
   
    $line = "OutputPath = $OPValue";
    print PROJ "$line $endingTag\n";
   }
  }
  elsif ($line =~ /HintPath/)
  {
   #remove quotes
   $tempLine = $line;
   $tempLine =~ s/\"//g;
   #get the file name
   (@Temp) = split(/\\/,$tempLine);
   $File = pop(@Temp);
   $File =~ s/\n//g;
   $sFile = "\\$File";
   #look for a matching file name in the Dependencies
   $Match = '';
   foreach $aDep ($AllDeps->getList())
   {
    if ($aDep eq $File || $aDep =~ /\Q$sFile\E$/i)
    {
     $Match = $aDep;  
    } 
   }
   
   if ($Match eq "") #no matches, leave the HintPath line alone
   {
    print PROJ "$line $endingTag\n";
   }
   else #replace the HintPath line with the absolute path reference to the dependency
   {
    $FullPath = new Openmake::File($Match);
    print PROJ "HintPath = \"" . $FullPath->getAbsolute ."\"" . $endingTag ."\n";
   }
  } 
  elsif ($line =~ /RelPath/)
  {
   print PROJ "$line $endingTag\n";
   
   # -- Commented out this code because it was a hold over from an earlier version of the script.
   #    The old version copied all Dependencies into the Build Directory. The new version copies
   #    all of the Dependencies relative to the Build Directory
   #
   #
   
   #if ($line =~ /\\/)
   #{
    # Removing relative path if any and then set the RelPath
    # attribute to just the file name
                 
    #(@Temp) = split(/\\/,$TargetFile);
    #$FileOnly = pop(@Temp);
    #$RelPath = '"' . $FileOnly . '"';
    #$line = "RelPath = $RelPath";
    #print PROJ "$line\n";
    #push(@dellist,$FileOnly);
   #}
   #else
   #{
   # print PROJ "$line";
   # push(@dellist,$FileOnly);
   #}
  }
  elsif ($line =~ /Link/)
  {
   # Skip this line - Must be omitted from temp project
   next;
  }
  elsif ($line =~ /ProjectType\s+=\s+"Web"/ ) 
  {
   # This is a webproject, set index
   $WebProject = 1;
   $currentWebProject = 1;
   print PROJ "$line $endingTag\n";
  } 
  else
  {
   print PROJ "$line $endingTag\n";
  }
 }  
 close(PROJ);
 ### Done Processing Temporary PROJ file ###
 
 
 # We need to rename the temp PROJ file ($Outproj) to temporarily replace the original PROJ file ($Proj).
 # First, backup the original PROJ file ($Proj) so we can restore it when the build is complete

 my $renProj;
 my $relProj = '';
 my $tmpStr;
 
 foreach $tmpStr ($RelDeps->getExtList(qw(.vbproj .csproj)))
 {
  $relProj = new Openmake::File($tmpStr),last if ($Proj->get =~ /\Q$tmpStr\E/);
 }

 # get the modification time of the original project file so we can set our temp project file to the same mod. time
 $stat = stat($relProj->get);
 if($stat != ())
 {
  $modTime = $stat->mtime;
 }
 
 if ( -e $relProj->get )
 { 
  #Store file attributes for project file in a hash to later reset the correct attributes
  $relProjName = $relProj->get;
  $projAttribs = GetAttributes($relProjName);
  $attribs{$relProjName} = $projAttribs;
  
  #-- need to copy to a backup location
  $renProj = $relProj->get . "\.omtemp";
  #   Since we seem to copy the .vbproj file to the working directory,
  #   chmod it first
  chmod 0755, $relProj->get;
  copy( $relProj->get, $renProj );
 }
 else
 {
  #-- JAG - make sure that we will delete the project file that we copied from our omXXXX.vbp
  push ( @DeleteFileList, $relProj->get) unless $KeepScript =~ /YES/i;
 }
 
 push @DeleteFileList, $relProj->get . ".user" if (! -e $relProj->get . ".user");
 
 #-- rename the temp PROJ file ($Outproj) to the original ($Proj)
 $relProj->mkdir;
 copy ( $Outproj, $relProj->get);
 push ( @DeleteFileList, $Outproj) unless $KeepScript =~ /YES/i;
  
 # add the Backup PROJ file to a RENAME array
 push ( @RenamedProjects, $renProj);
 
 #-- update the mod. time for our renamed project
 utime $modTime,$modTime,$relProj->get;

 #-- if this is a web project, we have to address the web server
 #   The subroutine resets the IIS webserver, and returns the original
 #   directory pointed at by the Target.
 my $cwd = cwd;
 
 #-- Add this project's target name to the @TargetCleanup array. Before calling DEVENV, we have to delete
 #   all existing versions of the Targets in the Build Directory and its sub-directories. If we don't do this,
 #   older versions of the Targets could cause DEVENV to throw Version Errors.
 push ( @TargetCleanup , $ProjTarget );
 
 #-- All projects except WEB projects get referenced in the DEVENV build command by their project name.
 #   set $buildtarget for the compile command.
 $buildtarget = $relProj->get;
 if ( $currentWebProject )
 {
  #-- JAG - has to point all the way down the path
  #($retcode, $WebPath) = &IISWebPath( $relProj->getF, $cwd );
  my $webbuilddir = $cwd . "/" . $relProj->getP;
  $webbuilddir =~ s/\//\\/g;
  ($retcode, $WebPath) = &IISWebPath( $relProj->getF, $webbuilddir , $relProj->getP);#, $OPValue );
  
   #$OrigWebPaths{$relProj->getF} = $WebPath;
  
  $OrigWebPaths{$relProj->getF} = $WebPath;
  $RelWebPaths{$relProj->getF} = $relProj->getP;

  
  if ( $retcode ) 
  {
   $RC = 1;
   $StepDescription = "Visual Basic 7 Configuring IIS";
   $StepError = "Cannot access 'IIS://Localhost/W3SVC/1/Root'. Is IIS Configured?";
   omlogger("Final",$StepDescription,"ERROR:","$StepError.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
  } 
  
  #-- since this is a WEB project, reset the $buildtarget to the web project's URL. $buildtarget is used by 
  #   the DEVENV compile command if om is only building 1 project not a full solution build.
  $buildtarget = "http://localhost/" . $relProj->getF  . "/" . $relProj->getFE;
 
  #-- add the .webinfo file to the delete list
  push @DeleteFileList, $relProj->getFE . ".webinfo";
 }
}
 
 
##############################
sub ValidateVDProj ($)
##############################
{
 #-- This subroutine parses the passed in .VDPROJ and looks for
 #   any .DLL and .EXE dependencies to make sure they exist locally.
 #   If they don't exist locally, we attempt to copy them local.
 #
 #   Inputs: 
 #    $vdproj -- the Openmake::File object that represents the .VDPROJ file
 #	         we are parsing.
 #
 
 use Openmake::FileList;
 
 my $vdproj = shift; #-- this is the .VDPROJ object
 
 my @copyLocal; #-- the array that will hold any dependencies that need to be copied
 
  # Read the .VDPROJ project file
  my $VdProj = $vdproj->get;
  if ( ! open(VDPROJ,"$VdProj") ) 
  {
   # -- Log the error 
   $StepDescription = "Unable to validate .VDPROJ file dependencies. Can't open source file: $VdProj\n";
   $RC = 0;
   omlogger("Intermediate",$StepDescription,"WARNING:","$StepDescription",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
   push(@DeleteFileList,$Log);
   push(@DeleteFileList,@dellist);
  }
  else
  {
   @vdprojLines = <VDPROJ>;
   close(VDPROJ);
 
   ######Process Source VDPROJ lines
   foreach $line (@vdprojLines)
   {
    chomp($line);  # remove trailing carriage returns to be able to pattern match
    $line =~ s/^\s+//g; 
    if($line =~ /^\"SourcePath\" = \"8:/)
    {
     # strip off the beginning of the line and any quotes and convert double slashes to single slashes
     $dep = $line;
     $dep =~ s/^\"SourcePath\" = \"8://;
     $dep =~ s/\"//g;
     $dep =~ s/\\\\/\\/g;
     
     # check to see if it's a DLL or EXE dependency
     if($dep =~ /.DLL$/i || $dep =~ /.EXE$/i)
     {
      # add the reletavie path from the Build Directory to the .VDPROJ file onto the dependency
      $relPath = $vdproj->getP;
      $dep = $relPath . "\\" . $dep;
      
      # if it doesn't exist, add it to the @copyLocal array
      if(! -e $dep )
      {
       push( @copyLocal , $dep );       
      }
     }
    }
   }
   
   #-- if @copyLocal contains any elements, construct an Openmake::FileList object using @copyLocal
   #   and pass it into CopyLocal;
   if(@copyLocal != ())
   {
    @AllDeps = unique($AllDeps->getExt(qw(.DLL .EXE)));
    foreach $relFile ( @copyLocal )
    {
     @Match = grep( /\Q$relFile\E$/i,@AllDeps);
     $copyFile = shift @Match;
     copy( $copyFile , $relFile );     
    }
    
    #$list = Openmake::FileList->new(@copyLocal);
    #CopyLocal($list , $AllDeps);
   }
  }
 }



##############################
sub IISWebPath ($$)
##############################
{
 #-- This subroutine reconfigures the IIS server to point a
 #   virtual directory that matches to $webproj at the
 #   directory passed. It returns the directory that the virtual 
 #   web directory was previously set.
 #
 #   Web projects seem to require that the webserver have a 
 #   path of http://localhost/<Project Name>
 #   which points to the current build directory.
 #
 #   Inputs: 
 #    $webproj -- the name of the project being built 
 #                e.g. 'VBApp'
 #    $newpath -- the directory location on the build machine
 #                at which to point the IIS webserver
 #                eg. 'C:\OpenmakeBuilds\OMProject\DEVL\VBApp'
 #
 #                If you pass $newpath as null, this subroutine
 #                will delete the webserver virtual directory.
 #    $relpath -- the relative path to look for in the IIS Virtual
 #                Directory paths
 #   $builddir -- It seems that web dlls end up in the bin/ dir
 #                below the webpath (ie. http://localhost/<proj>/bin/<dll>
 #                Set this directory to our build directory
 #
 #   Returns:
 #    $errcode -- 0 for good, 1 for bad.
 #    $oldpath -- the location at which the server previously
 #                pointed
 #                eg. 'C:\Webserver\VBApp'
 #                This might be null if nothing is defined
 #
 use Win32::OLE;
 use File::Path;
 
 my $webproj = shift; #-- this is the name of the web project
 my $newpath = shift; #-- this is the directory on the build machine to point at
 my $relpath = shift; #-- this is the relative path to match against the IIS Virtual Directory paths
# my $builddir = shift; #-- this is the final builddirectory
# $builddir =~ s/^"//;
# $builddir =~ s/"$//;
 
 #-- return
 my $errcode = 0;
 my $oldpath = '';

 #-- get an object reference to the web service
 my $IIS = Win32::OLE->GetObject("IIS://Localhost/W3SVC");

 #-- attempt to read the number of webservers
 my $temp = "AccessFlags";
 my $pathref = $IIS->GetDataPaths($temp, 0);

 my ($IISServer, $IISApp, $IISAppRoot);
 #-- find the webserver that matches to our project, if it 
 #   exists
 #   IISServer is the server that is supporting the directory;
 #   it's what gets started and stopped
 #   $IISApp is the directory itself; it's what we change to 
 #   point at the build directory.
 foreach my $dir ( @{$pathref} ) {
  #if ( $dir =~ /$webproj$/ ) {
   $dir =~ /(.+?\d+)\/Root/;
   my $rootdir = $1;
   $IISServer = Win32::OLE->GetObject($rootdir);
   $IISAppRoot = Win32::OLE->GetObject( $rootdir . "/Root" );
   $IISApp = Win32::OLE->GetObject($dir);
   $path = $IISApp->Get("Path");

   if($path =~ /\Q$relpath\E$/i)
   {
    # -- get the alias of the virtual directory from the $dir value because the alias may not
    #    match the Web Project's name
    @temp = split(/\//,$dir);
    $IISVirtualDirName = pop(@temp);
    last;  
   }
  # last;
  #}
 } 

 if ( $IISApp  ) {
  #-- we have both $IISApp and $IISServer
  
  #-- get the old path
  $oldpath = $IISApp->Get("Path");

  #-- stop the service
  if( $IISServer )
  {
   $IISServer->Stop;
  }

  #-- change the path, if new path exists. Otherwise
  #   delete the directory
  if ( $newpath ) {
   $IISApp->Put("Path", $newpath);
   #-- allow read and write access
   $IISApp->Put("AccessFlags", 3);
   $IISApp->SetInfo;
   
  } else {
   #-- delete the directory,
   $IISAppRoot->Delete( "IIsWebVirtualDir", $IISVirtualDirName );
  }
  
 } else  
 {
  #-- if we don't have an existing IISApp, we create it
  #   on the first webserver.
  $IISServer = Win32::OLE->GetObject("IIS://Localhost/W3SVC/1");
  if ( ! $IISServer )
  { 
   print "Cannot access 'IIS://Localhost/W3SVC/1'. Is IIS Configured?\n";
   $errcode = 1;
   return ($errcode, $oldpath);
  }
 
  #-- stop the service
  $IISServer->Stop;

  #-- create a new virtual directory
  my $IISAppRoot = Win32::OLE->GetObject("IIS://Localhost/W3SVC/1/Root");
  # set the $IISVirtualDirName to the $webproj value by default
  $IISVirtualDirName = $webproj;
  $IISApp = $IISAppRoot->Create("IIsWebVirtualDir", $IISVirtualDirName );
  $IISApp->SetInfo;
  $IISApp->Put("Path", $newpath);
  #-- allow read and write access
  $IISApp->Put("AccessFlags", 3);
  $IISApp->SetInfo;

 }
  
 #-- start the server
 $IISServer->Start;
 
 #-- .NET holds the Web Project directory location in cache.  At compile time, DEVENV compares the cached
 # Web Project directory to the IIS Virtual directory. If they don't match, it won't build. We need to delete 
 # the .NET Web Project cache directory so Openmake can build using the altered IIS virtual directory
 #
 # The CACHE location is: %USERPROFILE%\VSWebCache\<machine name>\<web project name> 
 
 # get the USERPROFILE environment variable
 $userProfile = $ENV{USERPROFILE};
 # get the COMPUTERNAME environment variable
 $machineName = $ENV{COMPUTERNAME};
 # delete the cache directory
 $delDir = "$userProfile\\VSWebCache\\$machineName\\$IISVirtualDirName";
 rmtree($delDir,0,0);
 
 return ($errcode, $oldpath) ;
 
}

##############################
sub GetAttributes
##############################
{
 $tmp = shift;
 $tmpattrib = "";
 $attrib = "";
 @attribs = ();
 @tmp = ();
 @tmp = split(/ /, `attrib \"$tmp\"`);
 pop @tmp;
 foreach (@tmp)
 {
  $attrib = $attrib . $_ if $_ =~ /\w+/;
 }
 @attribs = split(//,$attrib);
 foreach (@attribs)
 {
  $tmpattrib = $tmpattrib ."\+" . $_  . " ";
 }
 return $tmpattrib;
}


##############################
sub CleanUp ( )
##############################
{
 #-- This subroutine cleans up after the script has run. It 
 #   performs the following: 
 #
 #   1. strips .omtemp off all of the original VBPROJ and CSPROJ files
 #   2. touches every compiled object so they all have the same time stamp
 #   3. resets the IIS virtual directory to what it was before our build process adjusted it
 #
 #   Inputs: 
 #     N/A
 #
 #   Returns:
 #    N/A
 #

 #-- loop through all of the renamed VBPROJ and CSPROJ files and strip off .omtemp
 if ( @RenamedProjects != ()) 
 {
  foreach $file (@RenamedProjects)
  {
   $rename = $file;
   $rename =~ s/.omtemp$//;
   rename ( $file, $rename);  
  }
 }

 #-- Touch every compiled object so that they all have the same Time Stamp. Otherwise, om.exe could try to
 #   run a full Solution build again.
 @Touches = $RelDeps->getExtList(qw(.DLL .EXE .VBPROJ .CSPROJ .VDPROJ));
 $now = time;
 utime $now,$now,@Touches; 

 #-- pull original project file attributes from hash and set to touched project file
 foreach $project(@Touches)
 {
  if ($attribs{$project})
  {
   $attribArgs = $attribs{$project} . '"' . $project . '"';
   `attrib $attribArgs`;
  }
 }

 #-- reset the web directory to what it was before we started
 #   the build
 if ( $WebProject )
 {
  #&IISWebPath( $vbpobj->getF, $WebPath );
  foreach my $proj ( keys %OrigWebPaths ) 
  {
   &IISWebPath( $proj, $OrigWebPaths{$proj} , $RelWebPaths{$proj} );   
  } 
 }
}
