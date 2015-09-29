use File::Copy;
use File::Path;
use File::stat;
use File::Spec;
use File::Temp qw/tempfile/;
use Cwd;
#use Cwd qw{ abs_path realpath}; #Need this to resolve ../'s in paths. ADG 070606 - 6965

use Openmake::File;
use Fcntl ':flock';
use Openmake::File;
use Openmake::FileList;
use Win32::OLE;
use Win32::TieRegistry;
use Win32::File qw{ GetAttributes SetAttributes};
use 5.008_000;

$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Dot\040Net\040Solution\040Build.sc,v 1.56 2011/01/03 21:44:48 steve Exp $';
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

 $ScriptVersion = "$module, v$version";
}

#########################################
#-- Load Openmake Variables from Data File
#   Uncomment Openmake objects that need to be loaded
@PerlData = $AllDeps->load("AllDeps", @PerlData );
@PerlData = $RelDeps->load("RelDeps", @PerlData );
#  @PerlData = $NewerDeps->load("NewerDeps", @PerlData );
@PerlData = $TargetDeps->load("TargetDeps", @PerlData );
@PerlData = $TargetRelDeps->load("TargetRelDeps", @PerlData );
#  @PerlData = $TargetSrcDeps->load("TargetSrcDeps", @PerlData );

####### Setting Compiler Choice and Search Behavior #######

#-- Create semaphore file to make sure only one MBuild or Devenv is exectuded at a time for a given Build Directory
$build_dir_lockfile = "MSBuild_Devenv_BuildDir.omlock";
open BUILDDIR_LOCKFILE, "> $build_dir_lockfile";
flock BUILDDIR_LOCKFILE, LOCK_EX;

#-- evaluate debug ENV
our $Debug = 1 if ( $ENV{OM_DNET_DEBUG});

#-- evaluate the sleep time and max sleep for locking during web builds
our $Sleep_Time = 30;
our $Max_Sleep = 100;
#-- code to lock for solution builds
our $This_Locked = 0;
our $Lock_FH = "LOCK";
our $Lock_File = ($RelDeps->getExt(qw(.SLN)))[0] . '.lck';
our $FullSln = ($TargetDeps->getExt(qw(.SLN)))[0];
our $RelSln = ($TargetRelDeps->getExt(qw(.SLN)))[0];
our @SLNSourceLines  = ();
our @RenmaedItems = ();
our $containsWebProj = "";
our $slnDir = "";

our $is_DNET_2005 = ( $BuildType =~ m{2005} and $BuildType =~ m{Solution} ); #-- for adam @ Phoenix
our $om_temp_prefix;
my @msbuild_projects;
my @msbuild_clean_projects;

our %Project_Attributes;
our $Cwd;

#-- add mapping between biztalk configurations to normal configurations
our %BizTalk_Cfg = ( "RELEASE" => 'Development',
                     "DEBUG"   => ''
                   );

@CompilersPassedInFlags = ("devenv", "msbuild");
$DefaultCompiler  = "devenv";

($Compiler,$Flags) = get_compiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);
if ( $Compiler =~ m{\s+})
{
 $Compiler = '"' . $Compiler . '"'; #add quotes if $CFG has spaces in it
}

$IntDirName = $IntDir->get;

$CFG = "Release" if not defined $CFG;
if ($CFG =~ /\s+/g)
{
 $CFG = '"' . $CFG . '"'; #add quotes if $CFG has spaces in it
}

my $cwd = cwd();
$Cwd = $cwd; #-- global version
$cwd =~ s{\\}{/}g;
$cwd =~ s{/}{\\}g;

# Check to see if the build is going to occur at the SOLUTION level
$sln = $FullSln;

#-- If there is a Solution file:
#
#   1. copy all RelDeps local
#   2. loop through the Solution file and create a temp project file for each referenced .VBPROJ and .CSPROJ Project

@slnParts = split /\\/, $sln;
$slnDir = @slnParts[-2];

#-- JAG - case FLS-259 -  needs to be outside sln scopy
my @vd_projects          = unique($RelDeps->getExt(qw(.VDPROJ)));

$isSolutionBuild = 0;
if($sln ne "")
{
 # flip the isSolutionBuild flag
 $isSolutionBuild = 1;

 $TargetName = $Target->get;
 if (-e $TargetName)
 {
  #-- Get time stamp of Target
  $TargetStat = stat($TargetName);
  $TargetTime = $TargetStat->mtime;
 }
 else
 {
  $TargetTime = 0;
 }
 #-- JAG - 06.15.05 - case 5891 - lock for sln builds
 _lock($sln);
 if (-e $TargetName)
 {
  #-- Get the new time stamp of Target after locking
  $NewTargetStat = stat($TargetName);
  $NewTargetTime = $NewTargetStat->mtime;
 }
 else
 {
  $NewTargetTime = 0;
 }

 # We need to check date and type stamps of targets so
 # that we don't repeat the solution build that rebuilds
 # all targets when not necessary.  ADG 06.30.2005
 if (($NewTargetTime > $TargetTime || $TargetTime > $TargetTimestamp) && ($TargetTime != 0))
 {
  $RC = 0;
  $StepDescription = " Target " . $TargetName . " Up To Date\n";
  omlogger("Final",$StepDescription,"SUCCESS:","$StepDescription succeeded.",$StepDescription, "","",$RC,$StepDescription);

  #if ( $is_DNET_2005  )
  #{
   #-- clean up
   my @cs_vb_projs = $RelDeps->getExt(qw(.VBPROJ .CSPROJ .JSPROJ));

   my ($om_temp_prefix, @msbuild_clean_projects ) = MSBuildProject( \@cs_vb_projs, $RelSln);
   MSBuildProjectClean( $om_temp_prefix, \@msbuild_clean_projects );
  #}
    #-- Release the semaphore file for the Build Directory
  flock BUILDDIR_LOCKFILE, LOCK_UN;
  close BUILDDIR_LOCKFILE;
  
  ExitScript($RC,@DeleteFileList);
 }

 @AllTargets = fix_CmdLineTargets( @CmdLineParmTargets);

 #-- get the Visual Basic and C# project dependencies from $AllDeps
 my @cs_vb_projs          = $RelDeps->getExt(qw(.VBPROJ .CSPROJ .JSPROJ));
 my @non_msbuild_projects; #-- either .VCPROJ, .BTPROJ (2005) or all in 2003

 #-- do elements that are specific for .NET 2005
 #-- JAG - 06.22.07 - we do this for all builds. This is because you might
 #   run an MSI build after a .NET 2005 build, and your temp 2005 projects
 #   don't get cleaned up
 ($om_temp_prefix, @msbuild_projects ) = MSBuildProject( \@cs_vb_projs, $RelSln);
 if ( $is_DNET_2005 )
 {
  #-- copy local the .sln file
  CopyExcludeLocal( Openmake::FileList->new( $FullSln ), Openmake::FileList->new( $RelSln), '.');

  #-- munge the sln file to ignore OMBuild projects
  #-- also modify to change IIS builds
  _modify_2005_sln( $RelSln);

  @non_msbuild_projects  = unique($RelDeps->getExt(qw(.VCPROJ .BTPROJ)));
 }
 else
 {
  @msbuild_projects = ();
  @non_msbuild_projects = unique($RelDeps->getExt(qw(.CSPROJ .VBPROJ .VCXPROJ .VCPROJ .BTPROJ)));
 }

 # only copy local if this is 2002/2003 or if we have VC or BT projects. Others (CS, VB, JS)
 # in 2005 use absolute paths internal to om/MSBuild generated temp .XXproj files
 if (!($is_DNET_2005))
 {
  @localres = CopyExcludeLocal($AllDeps,$RelDeps,'.', qw(.dll .exe));
 }
 elsif ( @non_msbuild_projects or @vd_projects )
 {
   @localres = CopyExcludeLocal($TargetDeps,$TargetRelDeps,'.', qw(.dll .exe));
 }

 #-- JAG - need to copy non-proj information if listed
 my @add_localres = CopyExcludeLocal($AllDeps,$RelDeps,'.', qw(.dll .exe .cs .vb .csproj .vbproj ));
 push @localres, @add_localres;

 if ( !( @msbuild_projects || @non_msbuild_projects || @vd_projects ))
 {
  $RC = 1;
  $StepError = 'No Project files (.XXproj) files have been defined as Dependencies. Please add project dependencies to the .NET Solution.';
  push(@CompilerOut,$StepError);
  omlogger("Final",$StepDescription,"ERROR:","$StepError.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
  #-- Release the semaphore file for the Build Directory
  flock BUILDDIR_LOCKFILE, LOCK_UN;
  close BUILDDIR_LOCKFILE;
 
  ExitScript($RC,@DeleteFileList);
 }
 elsif ( @msbuild_projects || @non_msbuild_projects )
 {
  open(SLN,"$sln");
  @SLNSourceLines = <SLN>;
  close(SLN);

  open( my $sfh, '<', $sln );
  #####################
  # Create a hash of webprojects paired with virtual directories
  #####################
  while ( <$sfh>)
  {
   if (($_ =~ /^\s*Project/) && ($_ =~ /http:/))
   {
    $webp = $_;
    $webp =~ s/^Project\(\"\{[\w\d\-]+\}\"\s*\) =//g;
    $webp =~ s/\,\s*\"\{[\w\d\-]+\}\"\s*$//g;
    $webp =~ s/\"//g;
    chomp($webp);
    @tempwebp = split /,/, $webp;
    %webphash->{ "$tempwebp[0]" } = "$tempwebp[1]";  # hash ref
   }
  }
  close $sfh;
  @HandleVCXProj = ();
  
  foreach my $project ( @msbuild_projects, @non_msbuild_projects )
  {
   next if ($project =~ /^http:/); #don't process rel projects that are URL's - om occassionally adds the URLs of web projects to reldeps

   # create temporary proj files
   my $tmp_proj = new Openmake::File($project);

   # check to see that the project is checked to build in sln file - if not, don't process the proj file ADG 08.18.05 6171
   ($isBuildProj, $containsWebProj, @SLNSourceLines) = CheckSlnFile($tmp_proj, $project, $RelSln, @SLNSourceLines);
   next if ($isBuildProj != 1); #Skip this project if it is not checked as a build proj in sln file.

   #Store file attributes for each project file in a hash to later reset the correct attributes
   #-- Store file attributes for each project file in a hash to later reset the correct attributes
   $project =~ s{/}{\\}g; #-- JAG - 01.14.08 - case FLS-216
   GetAttributes($project, $Project_Attributes{$project});
   if($project =~ /\.BTPROJ$/i) #ADG 08.08.05 - 5795 Added support for BizTalk project builds
   {
    CreateTempBTProj( $tmp_proj, $isSolutionBuild);
   }
   elsif($project =~ /\.VCPROJ$/i || $project =~ /\.VCXPROJ$/i )
   {
    CreateTempVCProj( $tmp_proj, $isSolutionBuild);
	push(@HandleVCXProj,$project) if ($project =~ /.vcxproj/i);
   }
   elsif( $project =~ m{\.(VB|CS)PROJ$}i and not $is_DNET_2005 )
   {
    CreateTempVBCSProj( $tmp_proj, $isSolutionBuild, $RelSln);
   }
  }
 }
}
else # Exit the script. There has to be a Solution file dependency to use the Solution Build Types
{
 $RC = 1;
 $StepError = "No .SLN files have been defined as Dependencies. The Solution Build Types require .SLN file dependencies.";
 push(@CompilerOut,$StepError);
 omlogger("Final",$StepDescription,"ERROR:","$StepError.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
 
  #-- Release the semaphore file for the Build Directory
  flock BUILDDIR_LOCKFILE, LOCK_UN;
  close BUILDDIR_LOCKFILE;

 ExitScript($RC,@DeleteFileList);
}

#-- validate any .VDPROJ files included in the build
foreach my $vdproj ( @vd_projects)
{
 ValidateVDProj( Openmake::File->new($vdproj));
}

CreateTempWebSln($RelSln, @SLNSourceLines) if ($containsWebProj);

#-- Run the Build
#
#   For Solution Builds, compile against the .SLN file: $CompilerArguments = "$slnFilename /build $CFG /nologo 2>&1";

# get the filename of of the solution file
@Temp = split(/\\/,$sln);
$SlnFile = $RelSln;
# Default to a full build of the .SLN file
my $platform_flag = "$CFG";
if ( $PLATFORM ) { $platform_flag = "$CFG|$PLATFORM"; }
$CompilerArguments = "\"$SlnFile\" /rebuild \"$platform_flag\" $Flags /nologo 2>&1"; #support spaces in configurations and solution files 11.10.04 AG 5276

if (scalar @HandleVCXProj == 0)
{
	if ( $Compiler =~ m{msbuild}i )
	{
	 $platform_flag = '';
	 if ( $PLATFORM ) { $platform_flag = "/p:Platform=$PLATFORM"; }
	 $CompilerArguments = "\"$SlnFile\"  /t:Rebuild /p:Configuration=$CFG $platform_flag /nologo 2>&1";
	}

	#-- JAG - 05.03.04 - case 3725 - deal with move from String to array
	if( ( scalar @CmdLineParmTargets ) == 1 ) #if one Target is passed in, use the /project option on the command line
	{
	 my $target = shift @CmdLineParmTargets;
	 if($target)
	 {
	  my $project = $ProjectTargetMap{$target};
	  if($project)
	  {
	   $CompilerArguments = "\"$SlnFile\" /rebuild \"$CFG\" $Flags /project \"$project\" /nologo 2>&1";#support spaces in configurations
	   if ( $Compiler =~ m{msbuild}i )
	   {
		$CompilerArguments = "\"$project\"  /t:Rebuild /p:Configuration=$CFG /nologo 2>&1";
	   }

	  }
	 }
	}
	#-- end JAG 05.03.04

	#-- Before calling DEVENV, we have to delete all existing versions of the Targets (held in @TargetCleanup
	#   in the Build Directory and its sub-directories. If we don't do this, older versions of the
	#   Targets could cause DEVENV to throw Version Errors.

	$Command = "$Compiler $CompilerArguments";
	$StepDescription = "Compiling Solution File $SlnFile";
	$RC = 0;
	omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);

	#-- JAG - 12.27.05 - grab STDOUT too
	@CompilerOut = `$Command 2>&1 `;
}
else 
{
  $StepDescription = "Compiling VCXProj Files";
  $Compiler = FirstFoundInPath("msbuild.exe");
  if ( $PLATFORM ) { $platform_flag = "/p:Platform=$PLATFORM"; }
  $CompilerArguments = "/t:Rebuild /p:Configuration=$CFG $platform_flag /nologo 2>&1";

  omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
  $RC = 0;
 foreach $project (@HandleVCXProj)
 {
 	 $platform_flag = '';
	 if ( $PLATFORM ) { $platform_flag = "/p:Platform=$PLATFORM"; }
	 $CompilerArguments = "\"$project\"  /t:Rebuild /p:Configuration=$CFG $platform_flag /nologo 2>&1";
	$Command = "$Compiler $CompilerArguments";
	$StepDescription = "Compiling Solution File $SlnFile";

	#-- JAG - 12.27.05 - grab STDOUT too
	push(@CompilerOut,`$Command 2>&1`);
	if ( $? != 0 or grep m{ 0 succeeded, 0 failed, 0 skipped}, @CompilerOut) #-- JAG && LL - case FLS-303
    {
     $RC = 1;
     $StepError = "$Compiler did not execute properly.\n";
    }
 }
 $? = $RC;
}

if ( $? != 0 or grep m{ 0 succeeded, 0 failed, 0 skipped}, @CompilerOut) #-- JAG && LL - case FLS-303
{
 $RC = 1;
 $StepError = "$Compiler did not execute properly.\n";
}
else
{
 $RC = 0;
 $StepError = "$StepDescription succeeded.\n";
}

push(@CompilerOut,$StepError);
omlogger("Final",$StepDescription,"ERROR:","$StepError",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);

# clean up the build directory
#-- JAG - add sln to argument (to check for touching of dlls)
#-- JAG - add $om_tempprefix \@msbuild_projects
CleanUp( $sln, $om_temp_prefix, \@msbuild_projects);

#-- Release the semaphore file for the Build Directory
flock BUILDDIR_LOCKFILE, LOCK_UN;
close BUILDDIR_LOCKFILE;

ExitScript($RC,@DeleteFileList);

##############################
sub CreateTempVBCSProj
##############################
{
 my $Proj = shift;
 my $isSolutionBuild =shift;
 my $SlnFile = shift; #optional incase we need to look inside for webprojects

 if ( $Debug )
 {
  my $project = $Proj->get;
  print "DEBUG::CreateTempVBCSProj: running on '$project'";
  $isSolutionBuild ? print "; is a solution build" : print "; is not a solution build from '$SlnFile'";
  print "\n";
 }

 my $TargetDir = "";
 my $ProjectDir = "";
 my $OPValue = "";
 my $FullPath = "";
 my $FoundAssembly = 0;
 my $AssemblyMatch = 0;
 my $Log = $Target->getF . ".Log";
 my $ProjTargetFile = ''; #-- JAG - case 6615
 my $ProjTarget  = '';
 my $projExt = $Proj->getE;

 #-- do one copy to minimize object overhead
 my $vpath_string = $VPath->getString("\;","");
 my @allDeps = $AllDeps->getList();

 # Get the Project Directory from the $Proj filename
 if($Proj->get =~ /\\/)
 {
  @Temp = split(/\\/,$Proj->get);
  pop(@Temp);
  $ProjectDir = join("\\",@Temp);
  if($ProjectDir ne "")
  {
   $ProjectDir = $ProjectDir."\\";
  }
 }

 #-- see if we need footprinting
 my $fp_source;
 if (defined $FootPrintFile)
 {
  my $target  = $Target->get;
  my $project = $Proj->get;
  my $project_file = $Proj->getF();
  my $fp_file = $FootPrintFile->get;
  #-- get the right one in case we are creating the build for a different Tgt
  #   Note that this isn't 100% accurate, as it assumes the .XXproj file name
  #   is releated to the TGT name. HOwever, it's possible that one can write
  #   A.proj -> netA.dll
  opendir ( SUBMIT, '.submit' );
  my @files =  grep { -f && m{\.fp$} } map { $_ = '.submit/' . $_ } readdir SUBMIT;
  foreach my $file( @files )
  {
   my $newfile = Openmake::File->new($file)->getF;
   if ( $newfile =~ m[${project_file}_] )
   {
    $fp_file = $file;
    last;
   }
  }
  my $fp_ext;
  my $fp_type;
  my $fp_dir = $Proj->getDP();

  my $StepDescription = "Footprint for " . $target;
  my $Compiler = "";
  my @CompilerOut = ();

  if ( $projExt eq '.vbproj' )
  {
   $fp_ext = '.vb';
   $fp_type = 'VB.NET'
  }
  else
  {
   $fp_ext = '.cs';
   $fp_type = 'CS.NET'
  };
  ($tmpfh, $fp_source) = tempfile('omfp_XXXXX', DIR => $fp_dir, SUFFIX => $fp_ext, UNLINK => 0);
  close($tmpfh);
  push(@dellist, $fp_source) unless $KeepScript =~ /YES/i;

  #-- format the .fp file in the format expected by omident, with $OMBOM, etc
  GenerateFootPrint( {
                       'FootPrint' => $fp_file,
                       'FPSource'  => $fp_source,
                       'FPType'    => $fp_type,
                       'Compiler'  => 'no compile'
                     }
                    );
 }
 $fp_source = Openmake::File->new($fp_source)->getFE();

 # Read the Source project file
 my $SrcProj = $Proj->get;
 unless ( open(SRC,"$SrcProj") )
 {
  # -- Don't ExitScript if we can't open a Project File.
  #    to Local File System mapping. It will not neccesarily corrupt the build.
  $StepDescription = "Can't open source file: $SrcProj\n";
  $RC = 0;
  omlogger("Intermediate",$StepDescription,"WARNING:","$StepDescription",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
  push(@DeleteFileList,$Log);
  push(@DeleteFileList,@dellist);
 }
 @SourceLines = <SRC>;
 close(SRC);

 #-- Creating random name for temporary project file
 #   Returning temporaryfileHandle and name of temp project file
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
  
  #-- Release the semaphore file for the Build Directory
  flock BUILDDIR_LOCKFILE, LOCK_UN;
  close BUILDDIR_LOCKFILE;
  
  ExitScript($RC,@DeleteFileList);
 }

 ######Process Source PROJ lines
 foreach $line (@SourceLines)
 {
  chomp($_);  # remove trailing carriage returns to be able to pattern match
  $line =~ s/^\s+//g;
  $printedLine = 0;

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

  # Find version number of .NET - used in ClearWebCache subroutine
  if ($line =~ /ProductVersion/ && !$VSNetversion) #Check to see what version of .NET is used. Used to find VSWebCache location
  {
   $VSNetversion = $line;
   $VSNetversion =~ s/^.*\=//; # strip off "ProductVersion =" from front of line
   $VSNetversion =~ s/\"//g;   # remove quotes
   $VSNetversion =~ s/\d\.\d{4}$//; # shorten version number to two digits (7.X)
   $VSNetversion =~ s/\s//; # substitute blank space
   # $VSNetversion =~ s/\./\\\./; # escape dot
   chomp ($VSNetversion); #remove line break
  }

  if ($line =~ /AssemblyName/ && $FoundAssembly == 0)
  {
   $FoundAssembly = 1;
   if ($line !~ /^AssemblyName/) #AssemblyName may be nested in the line ADG 4.1.05
   {
    @assemblyNameParts = split(/ /,$line);
    @assemblyNameMatch = grep($_ =~ /AssemblyName/,@assemblyNameParts);
    $parsedLine = shift(@assemblyNameMatch);
    ($Key,$ProjAssembly) = split(/=/,$parsedLine);
   }
    else
   {
    ($Key,$ProjAssembly) = split(/=/,$line);
   }
   $ProjAssembly =~ s/^\s*//g;
   $ProjAssembly =~ s/\s*$//g;

   # set the Assembly name based on the Target's Name and see if it matches the $ProjAssembly
   $Assembly = $Target->getF;
   $Assembly = '"' . $Assembly . '"';

   if ($ProjAssembly eq $Assembly) #since they match, grab the $TargetDir from the Target Name
   {
    $TargetFile = $Target->getPFE;
    $TargetDir = $Target->getP if ($TargetFile =~ /\\/);
    $TargetDir .= "\\" if ($TargetDir !~ /\\$/);
    # flip the $AssemblyMatch flag
    $AssemblyMatch = 1;
    GenerateBillofMat($BillOfMaterialFile->get,$BillOfMaterialRpt,$TargetFile) if ( defined $BillOfMaterialFile);

    # set the $ProjTarget which gets used during the OUTPUT PATH handling
    $ProjTarget = $Target->getFE;
   }
   elsif($isSolutionBuild) # if it's a Solution Build, the $ProjAssembly may be found in $RelDeps
   {
    # check to see if the $ProjAssembly (the current PROJ's Assembly name) is included in the $RelDeps list
    @RelDeps = $RelDeps->getExtList(qw(.dll .exe));

    # grep for the current PROJ's Assembly name
    $ProjAssembly =~ s/\"//g;
    @Match = grep( /\\\Q$ProjAssembly.dll\E/i,@RelDeps);
    @MatchEXE = grep( /\\\Q$ProjAssembly.exe\E/i,@RelDeps);
    push(@Match,@MatchEXE);
    if(@Match != ())
    {
     # grab the $TargetDir from the found Dependency in $RelDeps
     $Match = shift @Match;
     GenerateBillofMat($BillOfMaterialFile->get,$BillOfMaterialRpt,$Match) if ( defined $BillOfMaterialFile);
     if($Match =~ /\\/)
     {
      @Temp = split(/\\/,$Match);
      # set the $ProjTarget which gets used during the OUTPUT PATH handling
      $ProjTarget = pop(@Temp);
      # join the remaining elements to get the Target Directory
      $TargetDir = join("\\",@Temp);
      $TargetDir .= "\\";
     }

     # flip the $AssemblyMatch flag
     $AssemblyMatch = 1;
    }
   }
   else # The Assembly name doesn't match the Target Name and it's not a Solution Build so we have to Exit
   {
    # flip the $AssemblyMatch flag
    $AssemblyMatch = 0;
   }

   if (!$AssemblyMatch)
   {
    #-- JAG 12.23.05 - 6629 - Don't error out on AssemblyName not in target name
    my $proj_file = $Proj->getDPFE();
    $StepError = "AssemblyName '$ProjAssembly' in project '$proj_file' doesn't match an Openmake Target: Inconsistent Target File\n";
    #$StepError = "AssemblyName doesn't match Openmake Target: Inconsistent Target File\n";
    push(@CompilerOut,$StepError);
    #$RC = 1;
    push(@DeleteFileList,$Log);
    push(@DeleteFileList,@dellist);
    close (PROJ);
    unlink $Outproj;
    omlogger("Intermediate",$StepDescription,"ERROR:","ERROR: $StepError",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), push(@DeleteFileList,$Target->get), ExitScript("1",@DeleteFileList) if ($RC != 0);
    return;
   }
   else
   {
    print PROJ "$line $endingTag\n" if ($printedLine == 0);
    $printedLine = 1;
   }
  }
  if ($line =~ /OutputPath/)
   {
    # Set the Output Path based on the $TargetDir value
    $OPValue = ".";
    if($TargetDir ne "\\" || $TargetDir ne "")
    {
     $OPValue = "$cwd\\$TargetDir";
    }
    $OPValue = '"' . "$OPValue" . '"';
    $line = "OutputPath = $OPValue";
    print PROJ "$line $endingTag\n" if ($printedLine == 0);
    $printedLine = 1;
   }
  if ($line =~ /HintPath/) #removed elsif condition since multiple matches may be nested in same line
  {
   #remove quotes
   if ($line !~ /^HintPath/) #HintPath may be nested in the line ADG 4.1.05
   {
    @HintPathParts = split(/ /,$line);
    @HintPathMatch = grep($_ =~ /HintPath/,@HintPathParts);
    $tempLine = shift(@HintPathMatch);
   }
   else
   {
    $tempLine = $line;
   }
   $tempLine =~ s/\"//g;
   #get the file name
   (@Temp) = split(/\\/,$tempLine);
   $File = pop(@Temp);
   $File =~ s/\n//g;
   chomp $File;
   $sFile = "\\$File";
   #look for a matching file name in the Dependencies
   $Match = '';
   @Match = grep(/\Q$File\E/, @CmdLineParmTargets);
   $Match = shift @Match;
   foreach $aDep (@allDeps)
   {
    if (($aDep eq $File || $aDep =~ /\Q$sFile\E$/i) && (!$Match))
    {
     $Match = $aDep;
     last;
    }
   }
   if ($Match eq "") #no matches, leave the HintPath line alone
   {
    print PROJ "$line $endingTag\n" if ($printedLine == 0);
    $printedLine = 1;
   }
   else #replace the HintPath line with the absolute path reference to the dependency
   {
    $FullPath = new Openmake::File($Match);
    print PROJ "HintPath = \"" . $FullPath->getAbsolute ."\"" . $endingTag ."\n" if ($printedLine == 0);
    $printedLine = 1;
   }
  }
  if ($line =~ /AssemblyFolderKey/)
  {
   # ADG 08.04.06 We don't want to write out this because it does a registry lookup instead of resolving through hintpath
   next;
  }
  if ($line =~ /Link/)
  {
   print PROJ "$line \n";
   # Skip this line - Must be omitted from temp project
   next;
  }
  if ($line =~ /ProjectType\s+=\s+"Web"/ )
  {
   # This is a webproject, set index
   print "DEBUG::CreateVBCSProj: Project is a webproject\n" if ( $Debug);
   $line =~ s/ProjectType\s+=\s+"Web"/ProjectType = "Local"/; #Change web mode to local to avoid IIS
   print PROJ "$line $endingTag\n" if ($printedLine == 0);
   $printedLine = 1;
  }
  elsif ( $line =~ m{</Include>} && $fp_source )
  {
   print PROJ "             <File\n";
   print PROJ "                    RelPath = \"$fp_source\"\n";
   print PROJ '                    SubType = "Code"' , "\n";
   print PROJ '                    BuildAction = "Compile"', "\n";
   print PROJ "                />\n";
   print PROJ $line, "\n";
   $printedLine = 1;
  }
  else
  {
   print PROJ "$line $endingTag\n" if ($printedLine == 0);
   $printedLine = 1;
  }
 }
 close(PROJ);
 ### Done Processing Temporary PROJ file ###


 #-- We need to rename the temp PROJ file ($Outproj) to temporarily replace the original PROJ file ($Proj).
 #   First, backup the original PROJ file ($Proj) so we can restore it when the build is complete
 my $renProj;
 my $relProj = '';
 my $tmpStr;
 my $thisRelProj = '';
 foreach $tmpStr ($RelDeps->getExtList(qw(.vbproj .csproj)))
 {
  $thisRelProj = $Proj->get;
  $relProj = new Openmake::File($tmpStr),last if ($thisRelProj =~ /\Q$tmpStr\E/ || $tmpStr =~ /\Q$thisRelProj\E/);
 }

 #-- JAG - 12.27.05 - case 6619
 return if ( $relProj eq '' );

 # get the modification time of the original project file so we can set our temp project file to the same mod. time
 $stat = stat($relProj->get);
 if($stat != ())
 {
  $modTime = $stat->mtime;
 }

 if ( -e $relProj->get )
 {
  #-- need to copy to a backup location
  #-- Not a regex, doesn't need \.
  $renProj = $relProj->get . ".omtemp";
  #   Since we seem to copy the .vbproj file to the working directory,
  #   chmod it first
  chmod 0755, $relProj->get;
  copy( $relProj->get, $renProj );
  push @RenamedItems, $renProj;
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
 push ( @RenamedItems, $renProj);

 #-- update the mod. time for our renamed project
 utime $modTime,$modTime,$relProj->get;

 #   directory pointed at by the Target.
 my $cwd = cwd();

 #-- Add this project's target name to the @TargetCleanup array. Before calling DEVENV, we have to delete
 #   all existing versions of the Targets in the Build Directory and its sub-directories. If we don't do this,
 #   older versions of the Targets could cause DEVENV to throw Version Errors.
 push ( @TargetCleanup , $ProjTarget );

 #-- Add this project to the $ProjectTargetMap Hash which maps Targets to Project Names. We need to do this so that
 #   if the user has passed a Target in as part of the om.exe command, we can, based on the FinalTarget name passed in,
 #   tell DEVENV to build the individual Project name contained in the Solution file.
 $p = $Proj->getF;
 $t = $TargetDir . $ProjTarget;
 #flip any "\" to "/" in the Target because this is how targets come in from om.exe
 $t =~ s/\\/\//g;
 $ProjectTargetMap{$t} = $p unless ( ! $t || $ProjectTargetMap{$t} );

 #-- All projects except WEB projects get referenced in the DEVENV build command by their project name.
 #   set $buildtarget for the compile command.
 $buildtarget = $relProj->get;
}

##############################
sub CreateTempVCProj
##############################
{
 my $Proj = shift;
 my $isSolutionBuild =shift;

 my $ProjectDir = "";
 my $projExt = "";
 my $ProjOutputFile = "";
 my $TargetDir = "";
 my $OPValue = "";
 my $FullPath = "";
 my $FoundOutputFile = 0;
 my $OutputFileMatch = 0;
 my $config = 0;
 my $config_match = 0;
 my $DifConfiguration = 0;
 my $OutputDirectory = 0;
 my $ConfigurationName = 0;
 my $VCProjName = "";
 my $ProjTargetFile = ''; #-- JAG - case 6615
 my $ProjTarget  = '';
 my $wroteFPDep = 0;

 $Log = $Target->getF . ".Log";

 #-- do one copy to minimize object overhead
 my $vpath_string = $VPath->getString("\;","");
 my @allDeps = $AllDeps->getList();

 #-- Get the Project Directory from the $Proj filename
 if($Proj->get =~ /\\/)
 {
  @Temp = split(/\\/,$Proj->get);
  pop(@Temp);
  $ProjectDir = join("\\",@Temp);
  if($ProjectDir ne "")
  {
   $ProjectDir = $ProjectDir."\\";
  }
 }

 #-- see if we need footprinting
 my $fp_source;
 if (defined $FootPrintFile)
 {
  my $target  = $Target->get;
  my $project = $Proj->get;
  my $project_file = $Proj->getF();
  my $fp_file = $FootPrintFile->get;
  #-- get the right one in case we are creating the build for a different Tgt
  #   Note that this isn't 100% accurate, as it assumes the .XXproj file name
  #   is releated to the TGT name. HOwever, it's possible that one can write
  #   A.proj -> netA.dll
  opendir ( SUBMIT, '.submit' );
  my @files =  grep { -f && m{\.fp$} } map { $_ = '.submit/' . $_ } readdir SUBMIT;
  foreach my $file( @files )
  {
   my $newfile = Openmake::File->new($file)->getF;
   if ( $newfile =~ m[${project_file}_] )
   {
    $fp_file = $file;
    last;
   }
  }
  my $fp_ext = '.cpp';
  my $fp_type = 'C';
  my $fp_dir = $Proj->getDP();

  my $StepDescription = "Footprint for " . $target;
  my $Compiler = "";
  my @CompilerOut = ();

  ($tmpfh, $fp_source) = tempfile('omfp_XXXXX', DIR => $fp_dir, SUFFIX => $fp_ext, UNLINK => 0);
  close($tmpfh);
  push(@dellist, $fp_source) unless $KeepScript =~ /YES/i;

  #-- format the .fp file in the format expected by omident, with $OMBOM, etc
  GenerateFootPrint( {
                       'FootPrint' => $fp_file,
                       'FPSource'  => $fp_source,
                       'FPType'    => $fp_type,
                       'Compiler'  => 'no compile'
                     }
                    );
 }
 $fp_source = Openmake::File->new($fp_source)->getFE();

 # Read the Source project file
 my $SrcProj = $Proj->get;
 my $sfh;
 unless ( open( $sfh, '<', $SrcProj ) )
 {
  #-- Don't ExitScript if we can't open a Project File.
  #   to Local File System mapping. It will not neccesarily corrupt the build.
  $StepDescription = "Can't open source file: $SrcProj\n";
  $RC = 0;
  omlogger("Intermediate",$StepDescription,"WARNING:","$StepDescription",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
  push(@DeleteFileList,$Log);
  push(@DeleteFileList,@dellist);

  #ExitScript($RC,@DeleteFileList);
 }
 @SourceLines = <$sfh>;
 close( $sfh );

 #-- Creating random name for temporary project file
 #   Returning temporaryfileHandle and name of temp project file
 $projExt = $Proj->getE;
 ($tmpfh, $Outproj) = tempfile('omXXXXX', SUFFIX => $projExt, UNLINK => 0);
 close($tmpfh);
 push(@dellist,$Outproj) unless $KeepScript =~ /YES/i;

 unless ( open( PROJ, '>', $Outproj) )
 {
  $StepError = "Can't write to file: $Outproj\n";
  $RC = 1;
  omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepError",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), push(@DeleteFileList,$Target->get), ExitScript("1",@DeleteFileList) if ($RC != 0);
  push(@DeleteFileList,$Log);
  push(@DeleteFileList,@dellist);
  
  #-- Release the semaphore file for the Build Directory
  flock BUILDDIR_LOCKFILE, LOCK_UN;
  close BUILDDIR_LOCKFILE;
  
  ExitScript($RC,@DeleteFileList);
 }

 ######Process Source PROJ lines
 #
 #-- Start by looping through the to determine the $OutputFile and the $TargetDir. We need to do this in a preliminary loop for
 #   .VCPROJ files because the OutputFile will come after the OutputDirectory in the XML
 $i = 0;
 my $foundAdditionalIncludes = 0;
 my $foundAdditionalLibraries = 0;
 my $foundVCLinkerOutputDir = 0;
 
 foreach my $line ( @SourceLines )
 {
  chomp($line);  # remove trailing carriage returns to be able to pattern match
  $line =~ s/\s+$//;
  $line =~ s/^\s+//;
  $line =~ s/\n//g;
  $CFG = Trim($CFG);

  if ($line =~ /^Name=/i)
  {
   my $junk = "";
   ($junk,$VCProjName) = split(/=/,$line);
  }
  if($line =~ /^\<Configuration$/)
  {
   $config = 1;
   next;
  }

  last if ($config_match == 1 && $line =~ /^\<\/Configuration/);
  
  if($config == 1)
  {
   $foundAdditionalIncludes if ($line =~ /AdditionalIncludeDirectories/ && $config_match == 1);
   $foundAdditionalLibraries if ($line =~ /AdditionalLibraryDirectories/ && $config_match == 1);
   $foundVCLinkerOutputDir if ($line =~ /OutputFile/ && $config_match == 1);
   
   if($line =~ /Name/ && $line =~ /$CFG/i && $config_match == 0)
   {
    ($Key,$ConfigName) = split(/=/,$line);
    ($ConfigurationName,$Platform) = split(/\|/,$ConfigName); #split ConfigName on pipe to separate platform from name
    $ConfigurationName = Trim($ConfigurationName);
    $Platform = Trim($Platform);
    $i++;
   }
   if ($CFG =~ /^$ConfigurationName$/i) #need to make sure we do our matching with the right configuration in the project file
   {
    if (($PLATFORM ne "" && $PLATFORM =~ /$Platform/i) || $Platform =~ /Any CPU/i)
    {
     $config_match = 1; #Stop looking for ConfigurationName
     if($line =~ /OutputDirectory/)
     {
      ($Key,$OutputDirectory) = split(/=/,$line);
      $OutputDirectory = Trim($OutputDirectory);
      $OutputDirectory =~ s/^\s*//g;
      $OutputDirectory =~ s/\s*$//g;
      $OutputDirectory =~ s/\"//g;
      $OutputDirectory =~ s|/|\\|g;
      if($OutputDirectory =~ /^\\$/ || $OutputDirectory =~ m|^\/$| || $OutputDirectory =~ /^\"\"$/)
      {
       $OutputDirectory = "";
      }
     }
    }
   }
  }
  if ($line =~ /OutputFile/ && $FoundOutputFile == 0 && $config_match == 1)
  {
   $FoundOutputFile = 1;
   ($Key,$ProjOutputFile) = split(/=/,$line);
   $ProjOutputFile =~ s/^\s*//g;
   $ProjOutputFile =~ s/\s*$//g;
   $ProjOutputFile =~ s/\"//g;
   $ProjOutputFile =~ s|/|\\|g;

   #need to get the concatenation of the OutputDir and OutputFile in Proj file
   #so that we can determine later if the target name matches the vcproj target name
   #if not, throw a warning message and change the temp vcproj to our target outdir and outfile
   $ProjTargetFile = $OutputDirectory . $DL . $ProjOutputFile;

   #-- The OutputFile from the .VCPROJ could have directories in front of the file name (often "$(OutDir)/"). We need to strip off the
   #   directories to perform the comparison.
   @Temp = split(/\\/,$ProjOutputFile);
   $ProjOutputFile = pop(@Temp);

   if ( $ProjOutputFile =~ m{\$\(ProjectName\)(\.\w+)$} )
   {
    $ProjOutputFile = $Proj->getF . $1;
   }

   # set the OutputFile name based on the Target's Name and see if it matches the $ProjOutputFile
   $OutputFile = $Target->getFE;

   if ($ProjOutputFile eq $OutputFile) #since they match, grab the Output Path from the Target Name
   {
    $TargetFile = $Target->getPFE;
    $TargetDir = $Target->getP if ($TargetFile =~ /\\/);
    # substitute out the ProjectDir because VCPROJ must have a RELATIVE PATH from the Project Directory as the Target Directory
    $TargetDir =~ s/\Q$ProjectDir\E//i;

    # flip the $OutputFileMatch flag
    $OutputFileMatch = 1;
    GenerateBillofMat($BillOfMaterialFile->get,$BillOfMaterialRpt,$TargetFile) if ( defined $BillOfMaterialFile);

    # set the $ProjTarget which gets used during the OUTPUT PATH handling
    $ProjTarget = $Target->getFE;
   }
   elsif($isSolutionBuild) # if it's a Solution Build, the $ProjOutputFile may be found in $RelDeps
   {
    # check to see if the $ProjOutputFile (the current PROJ's OutputFile name) is included in the $RelDeps list
    #@RelDeps = $RelDeps->getExtList(qw(.dll .exe));

    # grep for the current PROJ's OutputFile name
    $ProjOutputFile =~ s/\"//g;
    #@Match = grep( /\Q$ProjOutputFile\E/i,@RelDeps); #caused fals mismatches, should be matching on targets not deps 10\08\04 AG
    @Match = grep( /\Q$ProjOutputFile\E/i,@AllTargets);
    if(@Match != ())
    {
     # grab the Output Path from the found Dependency in $RelDeps
     $Match = shift @Match;
     GenerateBillofMat($BillOfMaterialFile->get,$BillOfMaterialRpt,$Match) if ( defined $BillOfMaterialFile);
     $Match =~ s|/|\\|g;
     if($Match !~ /\Q$ProjTargetFile\E/i)
     {
      $DifConfiguration = 1; #if not, later throw a warning message and change the temp vcproj to our target outdir and outfile
     }
     if($Match =~ /\\/)
     {
      @Temp = split(/\\/,$Match);
      # set the $ProjTarget which gets used during the OUTPUT PATH handling
      $ProjTarget = pop(@Temp);
      # join the remaining elements to get the Target Directory
      $TargetDir = join("\\",@Temp);
      # substitute out the ProjectDir because VCPROJ must have a RELATIVE PATH from the Project Directory as the Target Directory
      #if($ProjectDir) #only if exists, because a slash was being substituted out on an empty projdir
      #{
      # $TargetDir =~ s/\Q$ProjectDir\E//i;
      #}
     }

     # flip the $OutputFileMatch flag
     $OutputFileMatch = 1;
    }
    else # The OutputFile name doesn't match the Target Name and it's not a Solution Build so we have to Exit
    {
     # flip the $OutputFileMatch flag
     $OutputFileMatch = 0;
    }
   }
   if (!$OutputFileMatch)
   {
    #-- JAG 12.23.05 - 6629 - Don't error out on AssemblyName not in target name
    my $proj_file = $Proj->getDPFE();
    $StepError = "OutputFileName '$ProjOutputFile' in project '$proj_file' doesn't match Openmake Target: Inconsistent Target File\n";
    push(@CompilerOut,$StepError);
    #$RC = 1;
    push(@DeleteFileList,$Log);
    push(@DeleteFileList,@dellist);
    close (PROJ);
    unlink $Outproj;
    omlogger("Intermediate",$StepDescription,"ERROR:","ERROR: $StepError",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), push(@DeleteFileList,$Target->get), ExitScript("1",@DeleteFileList) if ($RC != 0);
    return;
   }
  }
  $i++;
 }

 #-- reset these, as we need to again go through the proj matching for the right configuration
 $config = 0;
 $config_match = 0;
 $OutputDirectory = 0;
 $ConfigurationName = 0;

 #-- Loop through the SourceLines again and actually write out the temporary file
 foreach $line (@SourceLines)
 {
 chomp $line; #--
 #$line =~ s/\s+$//;
 #$line =~ s/^\s+//;
 #$line =~ s/\n//g;
 if($line =~ /^\s*<Configuration$/)
 {
  $config = 1;
 }
 if($config == 1)
 {
  if($line =~ m{Name} && $line =~ /$CFG/i && $config_match == 0)
  {
   ($Key,$ConfigName) = split(/=/,$line);
   ($ConfigurationName,$Platform) = split(/\|/,$ConfigName); #split ConfigName on pipe to separate platform from name
   $ConfigurationName = Trim($ConfigurationName);
   $Platform = Trim($Platform);
   if ($CFG =~ /^$ConfigurationName$/i ) #need to make sure we do our matching with the right configuration in the project file
   {
    if (($PLATFORM ne "" && $PLATFORM =~ /$Platform/i) || $Platform =~ /Any CPU/i)
    {
     $config_match = 1; #Stop looking for ConfigurationName
    }
   }
  }
 }
 chomp($_);  # remove trailing carriage returns to be able to pattern match

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

  if ($config_match == 1)
  {
   if ($line =~ /VCLinkerTool/)
   {
    print PROJ "$line\n";
    if ($foundVCLinkerOutputDir == 0)
    {
     $key = "OutputDir";

     my $pre_dir = cwd();
     #$pre_dir = cwd() . $DL . $ProjectDir if ( $ProjectDir );
     my $look4 = "\\" . $VCProjName . ".";
     @t = grep(/\Q$look4\E/i,@AllTargets);

     $line = $key . '=' . '"' . $pre_dir . $DL . $t[0] . '"';#join back together only the target_name

     print PROJ "$line\n";
     $foundVCLinkerOutputDir = 1;
    }

    if ($foundAdditionalLibraries == 0)
    {
     $key = "AdditionalLibraryDirectories";
     $line = $key . '=' . '"' . $vpath_string . '"';
     print PROJ "$line\n";
     $foundAdditionalLibraries = 1;
    }
    next;
   }

   if ($line =~ /VCCLCompilerTool/ && $foundAdditionalIncludes == 0)
   {
    print PROJ "$line\n";
    $key = "AdditionalIncludeDirectories";
    $line = $key . '=' . '"' . $vpath_string . '"';
    print PROJ "$line\n";
    $foundAdditionalIncludes = 1;
    next;
   }

   if ($line =~ /OutputDirectory/)
   {
    if($TargetDir eq "")
    {
     $TargetDir = ".";
    }

    if($DifConfiguration == 1)
    {
     #-- this doesn't actually log anything except a blank line
     #$StepDescription = "Project's configuration did not match the configuration of the generated TGT, resulting in mismatch between the two Target names. These need to be synchronized. Writing Target to $Match";
     #$RC = 0;
     #omlogger("Intermediate",$StepDescription,"WARNING:","$StepDescription",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
     push(@DeleteFileList,$Log);
     push(@DeleteFileList,@dellist);
    }

    #$line = "OutputDirectory = " . '"' . $TargetDir . '"';
    print PROJ "$line $endingTag\n";
    next;
   }
   elsif ($line =~ /IntermediateDirectory/)
   {
    if($TargetDir eq "")
    {
     $TargetDir = ".";
    }
    #This was determined above in first pass of source lines in the target name to project's target name
    #comparison.  If they didn't match, still write out our target name (for om to know), but throw
    #warning message

    #$line = "IntermediateDirectory = " . '"' . $TargetDir . '"';
    print PROJ "$line $endingTag\n";
    next;
   }
   elsif ($line =~ /OutputFile/)
   {
    $line = Trim($line);
    $line =~ s|/|\\|; #make sure slashes are the same for splitting and popping
    ($key,$target_path) = split(/=/,$line);
    @tmp = split(/\\/,$target_path);
    $target_name = pop(@tmp); #get only target name since we already have full TargetDir
    my $pre_dir = cwd();
    #$pre_dir = cwd() . $DL . $ProjectDir if ( $ProjectDir );
    my $look4 = "\\" . $target_name;
    @t = grep(/\Q$look4\E/i,@AllTargets);
    
    #$line = $key . '=' . '"' . $pre_dir . $DL . $TargetDir . $DL . $target_name . '"';#join back together only the target_name
    $line = $key . '=' . '"' . $pre_dir . $DL . $t[0] . '"';#join back together only the target_name
    
    print PROJ "$line $endingTag\n";
    $config_match = 0; #we don't want to match any more on OutDir or IntDri
    next;
   }
  }

   if ( $fp_source && $wroteFPDep eq 0 && $line =~ /<ClCompile Include/)
   {
     my $fpfile =  cwd() . $DL . $fp_source;
	 
	 $fpfile =~ s/\//\\/g;
      print PROJ "<ClCompile Include=\"" . $fpfile . "\" />\n";
	  $wroteFPDep = 1;
   }
   
  $foundLib = 0 if ($line =~ /<Link>/);
  
  if ($line =~ /<\/Link>/ && $foundLib eq 0)
  {
      print PROJ "<AdditionalLibraryDirectories>" . $vpath_string . ";%(AdditionalLibraryDirectories)</AdditionalLibraryDirectories>\n"; 
  }

   if ($line =~ /<OutDir/)
  {
   my @parts = split(/>/,$line);
   $line = $parts[0] . ">";

     my $pre_dir = cwd();
     my $tf = $Target->getPFE;
     my $td = $Target->getP if ($tf =~ /\\/);
	
     my $s =  $pre_dir . $DL . $td . "\\";
	 $s =~ s/\//\\/g;
	 $line .= $s . "</OutDir>";
  }

  if ($line =~ /<AdditionalLibraryDirectories>/)
  {
  $foundLib = 1;
  $line = "<AdditionalLibraryDirectories>" . $vpath_string . ";%(AdditionalLibraryDirectories)</AdditionalLibraryDirectories>";
  }
  elsif ($line =~ /AdditionalLibraryDirectories/)
  {
   $line = Trim($line);
   $line =~ s|/|\\|; #make sure slashes are the same for splitting and popping
   ($key,$add_lib_dirs) = split(/=/,$line);
   $line = $key . '=' . '"' . $add_lib_dirs . $vpath_string . '"';
  }
  
  if ($line =~ /<ClCompile>/)
  {
   $foundInc = 0;
  }
  
  if ($line =~ /<\/ClCompile>/ && $foundInc eq 0)
  { 
   print PROJ "<AdditionalIncludeDirectories>" . $vpath_string . ";%(AdditionalIncludeDirectories)</AdditionalIncludeDirectories>\n"; 
  }
   
  if ($line =~ /<AdditionalIncludeDirectories>/)
  {
   $foundInc = 1; 
   $line = "<AdditionalIncludeDirectories>" . $vpath_string . ";%(AdditionalIncludeDirectories)</AdditionalIncludeDirectories>";
  }
  elsif ($line =~ /AdditionalIncludeDirectories/)
  {
   $line = Trim($line);
   $line =~ s|/|\\|; #make sure slashes are the same for splitting and popping
   ($key,$add_inc_dirs) = split(/=/,$line);
   $line = $key . '=' . '"' . $add_inc_dirs . $vpath_string . '"';
  }
  
  if ($line =~ /RelativePath/ && $line !~ /RelativePathToProject/) 
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
   foreach $aDep (@allDeps)
   {
    if ($aDep eq $File || $aDep =~ /\Q$sFile\E$/i)
    {
     $Match = $aDep;
     last;
    }
   }
   if ($Match eq "") #no matches, leave the HintPath line alone
   {
    print PROJ "$line $endingTag\n";
   }
   else #replace the HintPath line with the absolute path reference to the dependency
   {
    $FullPath = new Openmake::File($Match);
    print PROJ "RelativePath = \"" . $FullPath->getAbsolute ."\"" . $endingTag ."\n";
   }
  }
  
  if ($line =~ /RelativePath/ && $line !~ /RelativePathToProject/) 
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
   foreach $aDep (@allDeps)
   {
    if ($aDep eq $File || $aDep =~ /\Q$sFile\E$/i)
    {
     $Match = $aDep;
     last;
    }
   }
   if ($Match eq "") #no matches, leave the HintPath line alone
   {
    print PROJ "$line $endingTag\n";
   }
   else #replace the HintPath line with the absolute path reference to the dependency
   {
    $FullPath = new Openmake::File($Match);
    print PROJ "RelativePath = \"" . $FullPath->getAbsolute ."\"" . $endingTag ."\n";
   }
  }
  elsif ( $line =~ m{</Files} )
  {
   if ( $fp_source )
   {
    print PROJ "             <File\n";
    print PROJ "                    RelativePath = \".\\$fp_source\" >\n";
    print PROJ '                    <FileConfiguration', "\n";
    print PROJ '                         Name="Release|Win32">', "\n";
    print PROJ '                             <Tool', "\n";
    print PROJ '                                  Name="VCCLCompilerTool"', "\n";
    print PROJ '                                  UsePrecompiledHeader="0" />', "\n";
    print PROJ "                    </FileConfiguration>\n";
    print PROJ '                    <FileConfiguration', "\n";
    print PROJ '                         Name="Debug|Win32">', "\n";
    print PROJ '                             <Tool', "\n";
    print PROJ '                                  Name="VCCLCompilerTool"', "\n";
    print PROJ '                                  UsePrecompiledHeader="0" />', "\n";
    print PROJ "                    </FileConfiguration>\n";
    print PROJ "            </File>\n";
   }
   print PROJ "$line $endingTag\n";
  }
  else
  {
   print PROJ "$line $endingTag\n";
  }
 }

 close(PROJ);
 ### Done Processing Temporary PROJ file ###

 ##########
 #-- We need to rename the temp PROJ file ($Outproj) to temporarily replace the original PROJ file ($Proj).
 #   First, backup the original PROJ file ($Proj) so we can restore it when the build is complete
 my $renProj;
 my $relProj = '';
 my $tmpStr;

 foreach $tmpStr ($RelDeps->getExtList(qw(.vcproj .vcxproj)))
 {
  $relProj = new Openmake::File($tmpStr),last if ($Proj->get =~ /\Q$tmpStr\E/);
 }

 #-- JAG - 12.27.05 - case 6619
 return if ( $relProj eq '' );

 #-- Get the modification time of the original project file so we can set our temp project file to the same mod. time
 $stat = stat($relProj->get);
 if($stat != ())
 {
  $modTime = $stat->mtime;
 }

 if ( -e $relProj->get )
 {
  #-- need to copy to a backup location
  #-- JAG - it's not a REGEX, doesn't need \.
  $renProj = $relProj->get . ".omtemp";
  #   Since we seem to copy the .vbproj file to the working directory,
  #   chmod it first
  chmod 0755, $relProj->get;
  copy( $relProj->get, $renProj );
  push @RenamedItems, $renProj; 
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
 push ( @RenamedItems, $renProj);

 #-- update the mod. time for our renamed project
 utime $modTime,$modTime,$relProj->get;

 #-- Add this project's target name to the @TargetCleanup array. Before calling DEVENV, we have to delete
 #   all existing versions of the Targets in the Build Directory and its sub-directories. If we don't do this,
 #   older versions of the Targets could cause DEVENV to throw Version Errors.
 push ( @TargetCleanup , $ProjTarget );

#-- Add this project to the $ProjectTargetMap Hash which maps Targets to Project Names. We need to do this so that
#   if the user has passed a Target in as part of the om.exe command, we can, based on the FinalTarget name passed in,
#   tell DEVENV to build the individual Project name contained in the Solution file.

 $p = $Proj->getF;
 $t = $ProjectDir . $TargetDir . $DL . $ProjTarget;
 #flip any "\" to "/" in the Target because this is how targets come in from om.exe
 $t =~ s/\\/\//g;
 $ProjectTargetMap{$t} = $p unless ( ! $t || $ProjectTargetMap{$t} );

 #-- All projects except WEB projects get referenced in the DEVENV build command by their project name.
 #   set $buildtarget for the compile command.
 $buildtarget = $relProj->get;
}
##############################
sub CreateTempBTProj
##############################
{
 my $Proj = shift;
 my $isSolutionBuild =shift;

 my $ProjectDir = "";
 my $projExt = "";
 my $ProjAssemblyName = "";
 my $TargetDir = "";
 my $OPValue = "";
 my $FullPath = "";
 my $FoundAssemblyName = 0;
 my $AssemblyNameMatch = 0;
 my $config = 0;
 my $config_match = 0;
 my $DifConfiguration = 0;
 my $OutputPath = 0;
 my $ConfigurationName = 0;

 my $ProjTargetFile = ''; #-- JAG - case 6615
 my $ProjTarget  = '';

 #-- JAG - 01.17.06 - case 6455. BizTalk has Development and Deployment
 #   configurations by default. Customer uses 'Development'
 my %BT_Cfg = ( "RELEASE" => "Development"
              );

 my $cfg = uc $BT_Cfg{uc $CFG} || $CFG;
 $CFG = $cfg; #-- JAG - 06.11.07 - fix for Martin @ RAMQ
 $Log = $Target->getF . ".Log";

 #-- do one copy to minimize object overhead
 my $vpath_string = $VPath->getString("\;","");
 my @allDeps = $AllDeps->getList();

 if ( $Debug )
 {
  my $project = $Proj->get;
  print "DEBUG::CreateTempBTProj: running on '$project'";
  $isSolutionBuild ? print "; is a solution build" : print "; is not a solution build from '$SlnFile'";
  print "\n";
 }

 #-- Get the Project Directory from the $Proj filename
 if($Proj->get =~ /\\/)
 {
  @Temp = split(/\\/,$Proj->get);
  pop(@Temp);
  $ProjectDir = join("\\",@Temp);
  if($ProjectDir ne "")
  {
   $ProjectDir = $ProjectDir."\\";
  }
 }

 # Read the Source project file
 my $SrcProj = $Proj->get;
 unless ( open(SRC,"$SrcProj") )
 {
  #-- Don't ExitScript if we can't open a Project File.
  #   to Local File System mapping. It will not neccesarily corrupt the build.
  $StepDescription = "Can't open source file: $SrcProj\n";
  $RC = 0;
  omlogger("Intermediate",$StepDescription,"WARNING:","$StepDescription",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
  push(@DeleteFileList,$Log);
  push(@DeleteFileList,@dellist);

 }
 @SourceLines = <SRC>;
 close(SRC);

 #-- Creating random name for temporary project file
 #   Returning temporaryfileHandle and name of temp project file
 $projExt = $Proj->getE;
 ($tmpfh, $Outproj) = tempfile('omXXXXX', SUFFIX => $projExt, UNLINK => 0);
 close($tmpfh);
 push(@DeleteFileList,$Outproj) unless $KeepScript =~ /YES/i;

 unless ( open(PROJ,">$Outproj") )
 {
  $StepError = "Can't write to file: $Outproj\n";
  $RC = 1;
  omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepError",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), push(@DeleteFileList,$Target->get), ExitScript("1",@DeleteFileList) if ($RC != 0);
  push(@DeleteFileList,$Log);
  push(@DeleteFileList,@dellist);
  
    #-- Release the semaphore file for the Build Directory
  flock BUILDDIR_LOCKFILE, LOCK_UN;
  close BUILDDIR_LOCKFILE;
  
  ExitScript($RC,@DeleteFileList);
 }

 ######Process Source PROJ lines
 #
 #-- Start by looping through the to determine the $OutputFile and the $TargetDir. We need to do this in a preliminary loop for
 #   .VCPROJ files because the OutputFile will come after the OutputDirectory in the XML
 #-- Not true for BT. The order is
 #
#         <Build>
#            <Settings>
#                <EditorCommon/>
#                <ProjectCommon
#                    AssemblyName = "RAMQ.Orchestration_bt"
#                />
#                <Config
#                    Name = "Development"
#                    BpelCompliance = "True"
#                    GenerateDebuggingInformation = "True"
#                    EmbedTrackingInformation = "True"
#                    TreatWarningsAsErrors = "False"
#                    WarningLevel = "4"
#                    OutputPath = "bin\Development\"
 #

 $i = 0;
 while(!$FoundOutputFile && $i < @SourceLines)
 {
  $line = @SourceLines[$i];
  chomp($line);  # remove trailing carriage returns to be able to pattern match
  $line =~ s/\s+$//;
  $line =~ s/^\s+//;
  $line =~ s/\n//g;
  $CFG = Trim($CFG);

  if ($line =~ /AssemblyName/ && $FoundAssemblyName == 0 )
  {
   $FoundAssemblyName = 1;
   ($Key,$ProjAssemblyName) = split(/=/,$line);
   $ProjAssemblyName =~ s/^\s*//g;
   $ProjAssemblyName =~ s/\s*$//g;
   $ProjAssemblyName =~ s/\"//g;
   $ProjAssemblyName =~ s|/|\\|g;
  }

  if($line =~ /^\<Config$/)
  {
   $config = 1;
   $i++;
   next;
  }
  if($config == 1)
  {
   if($line =~ /Name/ && $line =~ /$CFG/i && $config_match == 0)
   {
    ($Key,$ConfigName) = split(/=/,$line);
    #($ConfigurationName,$Platform) = split(/\|/,$ConfigName); #split ConfigName on pipe to separate platform from name
    $ConfigurationName = Trim($ConfigurationName);
    $i++;
    next;
   }
   if ($CFG =~ /^$ConfigurationName$/i) #need to make sure we do our matching with the right configuration in the project file
   {
    $config_match = 1; #Stop looking for ConfigurationName
    if($line =~ /OutputPath/)
    {
     ($Key,$OutputPath) = split(/=/,$line);
     $OutputPath = Trim($OutputDirectory);
     $OutputPath =~ s/^\s*//g;
     $OutputPath =~ s/\s*$//g;
     $OutputPath =~ s/\"//g;
     $OutputPath =~ s|/|\\|g;
     if($OutputPath =~ /^\\$/ || $OutputPath =~ /^\/$/ || $OutputPath =~ /^""$/)
     {
      $OutputPath = "";
     }
    }
   }
  }

  if ( $OutputPath and $ProjAssemblyName )
  {
   #need to get the concatenation of the OutputDir and OutputFile in Proj file
   #so that we can determine later if the target name matches the vcproj target name
   #if not, throw a warning message and change the temp vcproj to our target outdir and outfile
   $ProjTargetFile = $OutputPath . $DL . $ProjAssemblyName;

   #-- The OutputFile from the .VCPROJ could have directories in front of the file name (often "$(OutDir)/"). We need to strip off the
   #   directories to perform the comparison.
   @Temp = split(/\\/,$ProjAssemblyName);
   $ProjAssemblyName = pop(@Temp);

   # set the OutputFile name based on the Target's Name and see if it matches the $ProjOutputFile
   $OutputFile = $Target->getFE;

   if ($ProjAssemblyName eq $OutputFile) #since they match, grab the Output Path from the Target Name
   {
    $TargetFile = $Target->getPFE;
    $TargetDir = $Target->getP if ($TargetFile =~ /\\/);
    # substitute out the ProjectDir because VCPROJ must have a RELATIVE PATH from the Project Directory as the Target Directory
    $TargetDir =~ s/\Q$ProjectDir\E//i;

    # flip the $OutputFileMatch flag
    $OutputFileMatch = 1;
    GenerateBillofMat($BillOfMaterialFile->get,$BillOfMaterialRpt,$TargetFile) if ( defined $BillOfMaterialFile);

    # set the $ProjTarget which gets used during the OUTPUT PATH handling
    $ProjTarget = $Target->getFE;
   }
   elsif($isSolutionBuild) # if it's a Solution Build, the $ProjOutputFile may be found in $RelDeps
   {
    # check to see if the $ProjOutputFile (the current PROJ's OutputFile name) is included in the $RelDeps list
    #@RelDeps = $RelDeps->getExtList(qw(.dll .exe));

    # grep for the current PROJ's OutputFile name
    $ProjOutputFile =~ s/\"//g;
    #@Match = grep( /\Q$ProjOutputFile\E/i,@RelDeps); #caused fals mismatches, should be matching on targets not deps 10\08\04 AG
    @Match = grep( /\Q$ProjOutputFile\E/i,@AllTargets);
    if(@Match != ())
    {
     # grab the Output Path from the found Dependency in $RelDeps
     $Match = shift @Match;
     GenerateBillofMat($BillOfMaterialFile->get,$BillOfMaterialRpt,$Match) if ( defined $BillOfMaterialFile);
     $Match =~ s|/|\\|g;
     if($Match !~ /\Q$ProjTargetFile\E/i)
     {
      $DifConfiguration = 1; #if not, later throw a warning message and change the temp vcproj to our target outdir and outfile
     }
     if($Match =~ /\\/)
     {
      @Temp = split(/\\/,$Match);
      # set the $ProjTarget which gets used during the OUTPUT PATH handling
      $ProjTarget = pop(@Temp);
      # join the remaining elements to get the Target Directory
      $TargetDir = join("\\",@Temp);
      #-- Dont' want this for BT?
      # substitute out the ProjectDir because VCPROJ must have a RELATIVE PATH from the Project Directory as the Target Directory
      #if($ProjectDir) #only if exists, because a slash was being substituted out on an empty projdir
      #{
      # $TargetDir =~ s/\Q$ProjectDir\E//i;
      #}
     }

     # flip the $OutputFileMatch flag
     $OutputPathMatch = 1;
    }
    else # The OutputFile name doesn't match the Target Name and it's not a Solution Build so we have to Exit
    {
     # flip the $OutputFileMatch flag
     $OutputPathMatch = 0;
    }
   }
   if (!$OutputPathMatch)
   {
    $StepError = "OutputFileName doesn't match Openmake Target: Inconsistent Target File\n";
    push(@CompilerOut,$StepError);
    $RC = 1;
    push(@DeleteFileList,$Log);
    push(@DeleteFileList,@dellist);
    omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepError",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), push(@DeleteFileList,$Target->get), ExitScript("1",@DeleteFileList) if ($RC != 0);
    return;
   }
  }
  $i++;
 }

 #reset these, as we need to again go through the proj matching for the right configuration
 my $config = 0;
 my $config_match = 0;
 my $OutputPath = 0;
 my $ConfigurationName = 0;


 #-- Loop through the SourceLines again and actually write out the temporary file
 foreach $line (@SourceLines)
 {
  #$line =~ s/\s+$//;
  #$line =~ s/^\s+//;
  #$line =~ s/\n//g;
  if($line =~ /^\<Configuration$/)
  {
   $config = 1;
  }
  if($config == 1)
  {
   if($line =~ (/\s+Name/ || /Name/) && $line =~ /$CFG/i && $config_match == 0)
   {
    ($Key,$ConfigName) = split(/=/,$line);
    ($ConfigurationName,$Platform) = split(/\|/,$ConfigName); #split ConfigName on pipe to separate platform from name
    $ConfigurationName = Trim($ConfigurationName);
    if ($CFG =~ /^$ConfigurationName$/i ) #need to make sure we do our matching with the right configuration in the project file
    {
     $config_match = 1; #Stop looking for ConfigurationName
    }
   }
  }
  chomp($_);  # remove trailing carriage returns to be able to pattern match

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

  if ($config_match == 1)
  {
   #-- JAG - change configuration name
   if ( $line =~ /Name/ && $line =~ /$ConfigurationName$/i)
   {

    (my $nline = $line ) =~ s|"$ConfigurationName"|"$cfg"|;
    print PROJ "$line $endingTag\n";
    next;

   }

   if ($line =~ /OutputPath/)
   {
    if($TargetDir eq "")
    {
     $TargetDir = ".";
    }

    if($DifConfiguration == 1)
    {
     #-- this doesn't log anything except a blank line
     #$StepDescription = "Project's configuration did not match the configuration of the generated TGT, resulting in mismatch between the two Target names. These need to be synchronized. Writing Target to $Match";
     #$RC = 0;
     #omlogger("Intermediate",$StepDescription,"WARNING:","$StepDescription",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
     push(@DeleteFileList,$Log);
     push(@DeleteFileList,@dellist);
    }

    #$line = "OutputPath = " . '"' . $TargetDir . '"';
    print PROJ "$line $endingTag\n";
    next;
   }
   elsif ($line =~ /IntermediateDirectory/)
   {
    if($TargetDir eq "")
    {
     $TargetDir = ".";
    }
    #This was determined above in first pass of source lines in the target name to project's target name
    #comparison.  If they didn't match, still write out our target name (for om to know), but throw
    #warning message

    #$line = "IntermediateDirectory = " . '"' . $TargetDir . '"';
    print PROJ "$line $endingTag\n";
    next;
   }
   elsif ($line =~ /AssemblyName/)
   {
    $line = Trim($line);
    $line =~ s|/|\\|; #make sure slashes are the same for splitting and popping
    ($key,$target_path) = split(/=/,$line);
    @tmp = split(/\\/,$target_path);
    $target_name = pop(@tmp); #get only target name since we already have full TargetDir
    $line = $key . '=' . '"' . $TargetDir . $DL . $target_name . '"';#join back together only the target_name
    print PROJ "$line $endingTag\n";
    $config_match = 0; #we don't want to match any more on OutDir or IntDri
    next;
   }
  }
  if ($line =~ /AdditionalLibraryDirectories/)
  {
   $line = Trim($line);
   $line =~ s|/|\\|; #make sure slashes are the same for splitting and popping
   ($key,$add_lib_dirs) = split(/=/,$line);
   $line = $key . '=' . '"' . $add_lib_dirs . $vpath_string . '"';
  }
  if ($line =~ /AdditionalIncludeDirectories/)
  {
   $line = Trim($line);
   $line =~ s|/|\\|; #make sure slashes are the same for splitting and popping
   ($key,$add_inc_dirs) = split(/=/,$line);
   $line = $key . '=' . '"' . $add_inc_dirs . $vpath_string . '"';
  }

  if ($line =~ /RelPath/)
  {
   #remove quotes
   $tempLine = $line;
   $tempLine =~ s/\"//g;
   #get the file name
   (@Temp) = split(/=/,$tempLine);
   $File = pop(@Temp);
   $File =~ s/\n//g;
   $sFile = $File;
   $sFile =~ s/^\s+//;
   $sFile =~ s/\s+$//;
   #look for a matching file name in the Dependencies
   $Match = '';
   foreach $aDep (@allDeps)
   {
    if ($aDep eq $File || $aDep =~ /\Q$sFile\E$/i)
    {
     $Match = $aDep;
     last;
    }
   }
   if ($Match eq "") #no matches, leave the HintPath line alone
   {
    print PROJ "$line $endingTag\n";
   }
   else #replace the HintPath line with the absolute path reference to the dependency
   {
    $FullPath = new Openmake::File($Match);
    print PROJ "RelPath = \"" . $FullPath->getAbsolute ."\"" . $endingTag ."\n";
   }
  }
  else
  {
   print PROJ "$line $endingTag\n";
  }
 }
 close(PROJ);

 #-- Add this project to the $ProjectTargetMap Hash which maps Targets to Project Names. We need to do this so that
 #   if the user has passed a Target in as part of the om.exe command, we can, based on the FinalTarget name passed in,
 #   tell DEVENV to build the individual Project name contained in the Solution file.
 $p = $Proj->getF;
 unless ( $TargetDir )
 {
  $TargetDir = $Target->getP;
  # substitute out the ProjectDir because VCPROJ must have a RELATIVE PATH from the Project Directory as the Target Directory
  $TargetDir =~ s/\Q$ProjectDir\E//i;
  $TargetDir .= "\\" if ($TargetDir !~ /\\$/);
 }
 $t = $TargetDir .  $Target->getFE;
 #flip any "\" to "/" in the Target because this is how targets come in from om.exe
 $t =~ s/\\/\//g;
 $ProjectTargetMap{$t} = $p unless ( ! $t || $ProjectTargetMap{$t} );

 push @DeleteFileList, $Proj->get . ".user" if (! -e $Proj->get . ".user");


 ### Done Processing Temporary PROJ file ###

}


##############################
sub ValidateVDProj
##############################
{
 #-- This subroutine parses the passed in .VDPROJ and looks for
 #   any .DLL and .EXE dependencies to make sure they exist locally.
 #   If they don't exist locally, we attempt to copy them local.
 #
 #   Inputs:
 #    $vdproj -- the Openmake::File object that represents the .VDPROJ file
 #          we are parsing.
 #

 my $vdproj = shift; #-- this is the .VDPROJ object
 my @copyLocal; #-- the array that will hold any dependencies that need to be copied

 my $inConfig = 0;
 my $inCfg    = 0;
 my $msi_name = '';

  # Read the .VDPROJ project file
  my $VdProj = $vdproj->get;
  my $vd_fh;
 unless ( open $vd_fh, '<', $VdProj  )
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
  local $_;
  while ( <$vd_fh> )
   {
   my $line = $_;
   chomp $line ;  # remove trailing carriage returns to be able to pattern match

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

    #-- JAG - 12.21.05 - case 6455 - find the msi
    if ( $inConfig and $inCfg )
    {
     #   "OutputFilename" = "8:Orchestration_BtApp_dep\\Release\\Orchestration_BtApp_dep.msi"
     if ( $line =~ /"OutputFilename"\s+=\s+"(.+?)"\s*$/ )
     {
      $inConfig = 0;
      $inCfg    = 0;
      $msi_name = $1;
      $msi_name =~ s/^\d+://;
      $msi_name =~ s/\\\\/\//g;
     }
    }

    $inConfig = 1 if ( $line =~ m{"Configuration} ); #"
    $inCfg    = 1 if ( $line =~ m{"$ENV{CFG}}i and $inConfig ); #"
   }

   #-- if @copyLocal contains any elements, construct an Openmake::FileList object using @copyLocal
   #   and pass it into CopyLocal;
  if( @copyLocal )
   {
   my @AllDeps = unique($AllDeps->getExt(qw(.DLL .EXE)));
   foreach my $relFile ( @copyLocal )
    {
    my @Match = grep( /\Q$relFile\E$/i,@AllDeps);
     $copyFile = shift @Match;
     
     if (!(length($copyFile)))
     {
        $copyFile="";
     }
     
     copy( $copyFile , $relFile );
    }
   }

   #-- JAG - case 6455 - add to project map
  my $p = $vdproj->getF;
  my $t = $msi_name;
   $t =~ s/\\/\//g;
   $ProjectTargetMap{$t} = $p unless ( ! $t || $ProjectTargetMap{$t} );

   ### Done Processing Temporary PROJ file ###
 }
 close $vd_fh;

}
##############################
#
sub CreateTempWebSln
##############################
# Since we no longer do web project builds that contact IIS, this routine prints out
# the modified solution file lines from &CheckSlnFile if the solution contains at least one
# web project. Modified solution lines, don't include URL's but instead the relative paths
# to the web project files from the solution dir.
# ADG 07.28.06
{
 my $slnName = shift;
 @SLNSourceLines = @_;
 ($tmpfh, $Outsln) = tempfile('omXXXXX', SUFFIX => "\.sln", UNLINK => 0);
 close($tmpfh);
 push(@dellist,$Outsln) unless $KeepScript =~ /YES/i;
 open(WEBSLN,">$Outsln");
 foreach $slnLine (@SLNSourceLines)
 {
  print WEBSLN $slnLine;
 }
 close WEBSLN;
 #-- need to copy to a backup location
 $renSln = $slnName . "\.omtemp";
 push @RenamedItems, $renSln;
 #   Since we seem to copy the .sln file to the working directory,
 #   chmod it first
 chmod 0755, $slnName;
 copy( $slnName, $renSln );
 #-- rename the temp SLN file ($Outsln) to the original ($slnName)
 #$relProj->mkdir;
 copy ( $Outsln, $slnName);
 push ( @DeleteFileList, $Outsln) unless $KeepScript =~ /YES/i;
 }

#############################
sub CheckSlnFile
#############################
# ADG 07.28.06
# CheckSlnFile does two things:
# 1) Checks to see if the project in a solution file is marked to be built as part of the solution. If not we
#    don't want to perform any additional analysis for that project (i.e., copy local and compile).
# 2) Check to see if there are any web projects in the solution. If there are, modify the solution files
#    array to substitute out the URL for the relative web project path, since we no longer contact IIS for web
#    project builds. Also, if a .webinfo file exists (if build in source directory, for example), we need to
#    rename that to .temp until the build is complete, so that devenv doesn't try to contact IIS.
{
 $projObj = shift;
 $projName = shift;

 my $cfg = $CFG;
 if ( $projName =~ m{\.btproj} )
 {
  $cfg = $BizTalk_Cfg{ uc $CFG};
 }

 my $slnName = shift;
 @SLNSourceLines = @_;
 $isBuildProj = 0;
 my $proj_id = '';
 $projFileName = $projObj->getFE;

 # Read the Solution file
 foreach (@SLNSourceLines ) #foreach sln file line look for the matching project ID to see if target should be built
 {
  if (($_ =~ /\Q$projFileName\E/i) and ! $proj_id ) #Web Project URL's contain forward slashes in sln
  {
   if ($_ =~ /(http:\/\/.*$projFileName)/) #check to see if this is a web project - will change sln later
   {
    @tmp = grep(/\Q$projName\E/,$TargetDeps->getList());
   
    $_ =~ s{$1}{$projName};
	$_ =~ s/\Q$projName\E/$tmp[0]/g if ($tmp[0] ne "");

    $containsWebProject = 1;
    if (-e "$projName\.webinfo") #Added this to rename .webinfo file for web projects to avoid IIS connection.
    {
     rename("$projName\.webinfo", "$projName\.webinfo\.omtemp");
     push(@RenamedItems, "$projName\.webinfo\.omtemp");
    }
   }
   @tmp = split(/=/,$_);
   $line = pop(@tmp);
   # last half of line contains 3 items separated by commas: project name , relative path to project , project ID
   @tmp = split(/\,/,$line);
   $proj_id = $tmp[2];
   $proj_id = Trim($proj_id);
  }
  #-- JAG - 06.12.07 - Change for .NET 2005 - they changed the text in the .sln file
  $build_configuration = 1 if ($_ =~ /GlobalSection\(ProjectConfiguration/i); #make sure we are in the write section
  if ($build_configuration == 1)
  {
   #-- VS 2005 added |.NET or |win32 to the configuration in front of the '.Build.0'
   #   so we account for that by allowing the $cfg to be followed by text
   # {15D66853-F262-4787-BB77-F0F6E20D6896}.Release|.NET.Build.0 = Release|.NET
   if ( m{\s*$proj_id\S*\.$cfg\S*Build.0}i) #must match CFG - Build.0 indicates the build checkbox is checked
   {
    $isBuildProj = 1;
    last;
   }
  }
 }
 return ($isBuildProj, $containsWebProject, @SLNSourceLines);
}

#############################
sub Trim
#############################
{
 my $arg = shift;
 $arg =~ s/\s+$//;
 $arg =~ s/^\s+//;
 $arg =~ s/\n//g;
 $arg =~ s/\"//g;
 $arg =~ s/\\\\/\\/g; #if no release or debug configuration is used, we are double back slashing the empty value 10/6/04 AG
 return $arg;
}


##############################
sub CleanUp
##############################
{
 #-- This subroutine cleans up after the script has run. It
 #   performs the following:
 #
 #   1. strips .omtemp off all of the original VBPROJ and CSPROJ files
 #   2. touches every compiled object so they all have the same time stamp
 #
 #   Inputs:
 #   Solution File (optional) -- look in solution file to determine which Projects were built on this call
 #   to Devenv
 #   om temp postfix (optional ) -- clean up temporary scripts made by MSBuildProject
 #
 #   Returns:
 #    N/A
 #
 my $sln = shift;
 my $om_temp = shift;
 my $msbuild_projs_ref = shift;

 #-- move temporary .NET 2.0 files back to temporary names
 if ( $msbuild_projs_ref )
 {
  MSBuildProjectClean( $om_temp, $msbuild_projs_ref );
 }

 #-- find temporary names and remove them
 if ( $KeepScript !~ /YES/i and $TotalSteps eq $CurrentStep )
 {
  #-- file::find the deps
  use File::Find;
  my @found_files;
  find({ wanted => sub{
                       if ( $_ =~ m{(_om_\d{8})\.\w{2}proj$} )
 {
                        push @tempfiles, $File::Find::dir . '/' . $_;
                       }
                      } }, '.');

 # my @tempfiles = grep { $_ =~ m{(_om_\d{8})\.\w{2}proj$} } $RelDeps->getExt(qw(.VBPROJ .CSPROJ .JSPROJ));
  my @tempfiles = grep { $_ =~ m{(_om_\d{8})\.\w{2}proj$} } @found_files;
  my @tempuserfiles = @tempfiles;
  map { $_ .= '.user'} @tempuserfiles;

#  push @DeleteFileList, @tempfiles;
#  push @DeleteFileList, @tempuserfiles;
 }

 #-- loop through all of the renamed VBPROJ and CSPROJ files and strip off .omtemp
 #-- pull original project file attributes from hash and set to touched project file
 if ( @RenamedItems != ())
 {
  foreach $file (@RenamedItems)
  {
   $rename = $file;
   $rename =~ s{\.omtemp$}{};
   chmod 0777,$file;
   chmod 0777,$rename;
   rename ( $file, $rename) unless $KeepScript =~ /YES/i;
  }
 }

 #-- Touch every compiled object so that they all have the same Time Stamp. Otherwise, om.exe could try to
 #   run a full Solution build again.

 my @Touches = ();
 if ( $sln )
 {
  print "DEBUG: Cleanup: using Solution File '$sln' to determine timestamp updating\n" if ( $Debug );
  @Touches = _touches( $sln);
 }
 else
 {
  print "DEBUG: Cleanup: using all Relative Dependencies to determine timestamp updating\n" if ( $Debug );
  @Touches = $RelDeps->getExtList(qw(.DLL .EXE .VBPROJ .CSPROJ .VDPROJ .VCPROJ));
 }
 if ( $Debug )
 {
  print "DEBUG: the current working directory is ", cwd(), "\n\n";

  print "DEBUG: Touching following files to common timestamp:\n";
  print "       \t", $_, "\n" foreach ( @Touches);

  foreach $project(@Touches)
  {
   if ( ! -e $project )
   {
    print "DEBUG ERROR: file '$project' doesn't exist!\n";
   }
   else
   {
    #my $mtime = (stat($project))[9];
    my $mtime;
    my @s = stat $project;
    if ( ref $s[0] ne 'SCALAR' )
    {
     $mtime = $s[0]->[9];
    }
    else
    {
     $mtime = $s[9];
    }

    my $lt = localtime($mtime);
    print "DEBUG: init  file times for '$project' $lt \n";
   }
  }
 }


 $now = time;
 $time = localtime($now);
 print "DEBUG: now is '$time'\n" if ( $Debug );

 my $files = 0;
 $files = utime $now,$now,@Touches;
 my $tfiles = scalar @Touches;
 if ( $files != $tfiles && $Debug )
 {
  print "Only able to touch $files out of $tfiles files\n";
 }

 #-- pull original project file attributes from hash and set to touched project file
 foreach $project(@Touches)
 {
  if ( $Debug )
  {
   my $mtime;
   my @s = stat $project;
   if ( ref $s[0] ne 'SCALAR' )
   {
    $mtime = $s[0]->[9];
   }
   else
   {
    $mtime = $s[9];
   }
   my $lt = localtime($mtime);
   print "DEBUG: file times for '$project' $lt \n";
  }
 }

 #-- need to do attribs outside of lookup.
 print "DEBUG: Cleanup: using all Relative Dependencies to determine timestamp updating\n" if ( $Debug );
 my @Attribs = $RelDeps->getExtList(qw(.DLL .EXE .VBPROJ .CSPROJ .VDPROJ .VCPROJ));
 foreach my $project ( @Attribs )
 {
  if ($Project_Attributes{$project})
  {
   SetAttributes( $project, $Project_Attributes{$project});
  }
 }
 #-- unlock workspace
 if ($sln)
 {
  _lock($sln);
 }
}

##############################
sub _lock
##############################
{
 my $SlnFile = shift;
 #-- see if the solution has a webproject.
 #-- stored as global
 #-- See if we locked the file
 if ( $This_Locked )
 {
  #-- Previously, we locked the file in the build. Unlock, continue
  print "DEBUG: _lock: Unlocking \n" if ( $Debug );
  flock( $Lock_FH, LOCK_UN);
  close $Lock_FH;
  unlink $Lock_File;
  $This_Locked = 0;
 }
 else
 {
  #-- Attempt to lock, continue build
  unless ( open( $Lock_FH, '>', $Lock_File ) )
  {
   print "DEBUG: _lock: Unable to open lock file 'Lock_File'\n" if ( $Debug );
   return 1;
  }
  my $sleep = 0;
  while ( ! flock( $Lock_FH, LOCK_EX | LOCK_NB ) )
  {
   print "DEBUG: _lock: Unable to lock lockfile '$Lock_File'. Sleeping $Sleep_Time\n" if ( $Debug );
   sleep $Sleep_Time;
   $sleep++;
   if ( $sleep == $Max_Sleep )
   {
    print "DEBUG: _lock: Maxed out sleep times waiting for lockfile '$Lock_File'\n" if ( $Debug );
    return 1;
   }
  }
  print "DEBUG: _lock: locked lockfile '$Lock_File'\n" if ( $Debug );
  $This_Locked = 1;
  return 0;
 }
}

#############
sub _touches
#############
{
 my $sln = shift;
 my @touches = ();

 #-- see if the solution has a webproject.
 #-- stored as global
 unless ( open ( SLN, $sln ))
 {
  print "DEBUG: _touches: Unable to open SolutionFile '$sln'\n"   if ( $Debug );
  return @touches;
 }

 #-- reverse ProjectTargetMap
 my %target_project_map = reverse %ProjectTargetMap;

 while ( <SLN> )
 {
  s/\\/\//g;
  chomp;

  #Project("{54435603-DBB4-11D2-8724-00A0C9A8B90C}") = "UQY2_GereParamAgenc_CpoApp_dep",
  if ( m|Project\(| )
  {
   my ( $key, $proj, $remain);
   ( $key, $remain) = split /\s*=\s*/, $_;
   ( $proj, $remain) = split /,/, $remain;

   $proj =~ s/^\s*"//;
   $proj =~ s/"\s*$//;
   print "DEBUG: _touches: Found Project '$proj'\n" if ( $Debug );
   my $target = $target_project_map{$proj};

   #-- case 6455 - see if this exist. If not, look in CmdLineParmTargets
   if ( $target and ( ! -e $target ) ) #-- make sure that $target is not undef
   {
    $target =~ s/\\/\//g;
    foreach my $cmdtarget ( @CmdLineParmTargets )
    {
     $cmdtarget =~ s/\\/\//g;
     if ( $cmdtarget =~ /\Q$target\E$/ )
     {
      $target = $cmdtarget;
      last;
     }
    }
   }

   if ( $Debug )
   {
    print "DEBUG: _touches: ";
    $target ? print "maps to Target '$target'\n" : print " with no matching Target\n";
   }

   push @touches, $target if ( $target );
  }
 }

 close SLN;
 return @touches;
}

#------------------------------------------------------------------
sub MSBuildProject
{
 my $msbuild_projects_list = shift;
 my $om_temp;
 my @return_projs;
 my %found; #-- check to see that the project file hasn't been listed twice due to relative ".." path issues
 my %rel2all = Openmake::SearchPath::RelPath2AbsPath( $::RelDeps, $::AllDeps);

 local $_;
 #-- instantiate the .NET solution
 # SBT 10.07.08 - FLS-475 - skip printing error. Routine will return due to no MSBuild projects found.
 my $dnet = Win32::OLE->new('OpenmakeNET2005'); # or print Win32::OLE->LastError(), "\n";

 #-- copy the om_temp projects to the real projects

 my @full_msbuild_projs = ();
 my %project_refs;
 foreach my $proj ( @{$msbuild_projects_list} )
 {
  $proj =~ s{\\}{/}g;
  #-- match to temporary for this project
  if ( $proj =~ m{(_om_\d{8})\.\w{2}proj$} )
  {
   $om_temp = $1;

   #-- test for duplicate projects
   my $real_name = '';
   $real_name = Cwd::realpath( $proj); #-- need real_path to resolve .. in names
   if ( ! -e $real_name )
   {
    my $StepError = "Cannot find temporary MSBuild script '$proj' at location '$real_name'.";
    omlogger ( { 'StepStatus' => 'Final',
                 'StepDescription' => 'MSBuildProject move',
                 'LastLine' => $StepError,
                 'RC' => 1,
                 'CompilerOut' => [ $StepError ]
               } );
    exit 1;
   }

   next if ( $found{$real_name});
   $found{$real_name} = 1;
   push @full_msbuild_projs, $proj;

   #-- see if this project makes a project reference to other
   $dnet->{ProjectFile} = $proj;
   $dnet->{SearchPath}  = '.';
   $dnet->Init();

   my $proj_ref = '';
   while ( ( $proj_ref = $dnet->getNextProjectReference() ) ne '' )
   {
    my @p = split /[\\\/]/, $proj;
    pop @p;
    my $new_proj = join '/', @p, $proj_ref;
    $new_proj = File::Spec->canonpath( $new_proj);

    #-- see if there is a project that matches tothe $omtemp
    @p = split /[\\\/]/, $new_proj;
    my $file = pop @p;
    my @f = split /\./, $file;
    my $ext = pop @f;
    $file = join '', @f, $om_temp;
    $file .= ".$ext";
    $new_proj = join '/', @p, $file;
    if ( -e $new_proj )
    {
     #-- do the test for overlapping
     my $real_name = Cwd::realpath( $proj); #-- need real_path to resolve .. in names
     $project_refs{$new_proj} = 1;

     next if ( $found{$real_name});
     $found{$real_name} = 1;

     push @full_msbuild_projs, $new_proj;
    }
   }
  }
  else
  {
   next;
  }
 }

 foreach my $proj ( @full_msbuild_projs )
 {
  #-- get the canonical name. Remove any '..' crap
  my $ofile = Openmake::File->new($proj);
  my $real_proj = $ofile->getF();
  $real_proj =~ s{$om_temp$}{};
  $real_proj = join ( '/', $ofile->getP(), $real_proj . $ofile->getE() );
  $real_proj =~ s{\\}{/}g;
  $proj =~ s{\\}{/}g;

  #-- see if it exists in the current directory, back it up
  if ( -e $real_proj )
  {
   #-- need to copy to a backup location
   #-- this doesn't do what we thought. It's not a REGEX, a straight '.' is fine
   $back_proj = $real_proj . ".omtemp";
   chmod 0755, $real_proj;
   copy( $real_proj, $back_proj);
   push @RenamedItems, $back_proj;
  }

  #-- move the temp file to the 'real' name
  #chmod 0755, $proj;
  copy ( $proj, $real_proj);
  #-- at this point, we need to set the timestamp to the same as the source code

  my @s = ();
  
  if ($rel2all{$real_proj} eq "")
  {
   my $tmp_real_proj = $real_proj;
   $tmp_real_proj =~ s/\\/\//g;
     @s = stat $rel2all{$temp_real_proj};
  }
  else
  {
    @s = stat $rel2all{$real_proj};
  }
  my $mtime;
  if ( ref $s[0] ne 'SCALAR' )
  {
   $mtime = $s[0]->[9];
  }
  else
  {
   $mtime = $s[9];
  }
  utime $mtime, $mtime, $real_proj;

  #-- Add to our list of returns
  push @return_projs, $real_proj;

  #-- at this point, need to update for the .fp and the assembly name
  #-- get the assembyName thru MSBuild .dll
  $dnet->{ProjectFile} = $real_proj;
  $dnet->{SearchPath}  = '.';
  $dnet->Init();

  #-- see if the project name has a path
  my $rfile = Openmake::File->new($real_proj);
  #$assembly_name = $rfile->getP() . '/' . $dnet->getAssemblyName();
  $assembly_name = $dnet->getAssemblyName();
  $assembly_name =~ s{\\}{/}g;
  $assembly_name =~ s{\$\(CFG\)}{$CFG}g;

  #-- update assemblyname for touches. Since we know that at build time
  #   we didn't throw an assembly name error, use TargetName
  my $head = $rfile->getF();
  $ProjectTargetMap{$assembly_name} = $head unless ( ! $assembly_name || $ProjectTargetMap{$assembly_name} );

  my $project_file = $rfile->getF();

  #-- Need to update footprinting.
  my $fp_source;
  if ( defined $BillOfMaterialFile and not $project_refs{$proj} )
  {
   my $target = $TargetFile;
   if ( ! $TargetFile )
   {
    $target = $assembly_name;
   }
   GenerateBillofMat( $BillOfMaterialFile->get,$BillOfMaterialRpt, $target) #-- case 7911
  }

  if (defined $FootPrintFile and not $project_refs{$proj} )
  {
   my $fp_file = $FootPrintFile->get();
   #-- get the right one in case we are creating the build for a different Tgt
   #   Note that this isn't 100% accurate, as it assumes the .XXproj file name
   #   is releated to the TGT name. HOwever, it's possible that one can write
   #   A.proj -> netA.dll
   opendir ( SUBMIT, '.submit' );
   my @files =  grep { -f && m{\.fp$} } map { $_ = '.submit/' . $_ } readdir SUBMIT;
   foreach my $file( @files )
   {
    my $newfile = Openmake::File->new($file)->getF();
    if ( $newfile =~ m[${project_file}_] )
    {
     $fp_file = $file;
     last;
    }
   }
   my $fp_ext;
   my $fp_type;
   my $fp_dir = $ofile->getDP();

   my $StepDescription = "Footprint for " . $target;
   my $Compiler = "";
   my @CompilerOut = ();

   if ( $ofile->getE() eq '.vbproj' )
   {
    $fp_ext = '.vb';
    $fp_type = 'VB.NET'; #-- need to update Footprint module to handle 2005
   }
   else
   {
    $fp_ext = '.cs';
    $fp_type = 'CS.NET'; #-- need to update Footprint module to handle 2005
   };
   ($tmpfh, $fp_source) = tempfile('omfp_XXXXX', DIR => $fp_dir, SUFFIX => $fp_ext, UNLINK => 0);
   close($tmpfh);
   push(@::DeleteFileList, $fp_source) unless $::KeepScript =~ /YES/i;

   #-- format the .fp file in the format expected by omident, with $OMBOM, etc
   GenerateFootPrint( {
                        'FootPrint' => $fp_file,
                        'FPSource'  => $fp_source,
                        'FPType'    => $fp_type,
                        'Compiler'  => 'no compile'
                      }
                     );
  }
  $fp_source = Openmake::File->new($fp_source)->getFE();

  #-- substitute the .fp file in the build XML for the given file
  $dnet->AddFootprintObject($fp_source);

  #-- clear the pre/post commands
  if ( $ignore_pre_post )
  {
   $dnet->ClearPrePostBuildTask();
  }
 }

 return ( $om_temp, @return_projs);

}

#------------------------------------------------------------------
sub MSBuildProjectClean
{
 my $om_temp = shift;
 my $msbuild_projs_ref = shift;

 #-- move the projects back to _om_temp, add to delete list
 foreach my $proj ( @{$msbuild_projs_ref})
 {
  my $ofile = Openmake::File->new($proj);
  my $real_proj = $ofile->getF();
  $real_proj .= $om_temp;
  $real_proj = join ( '/', $ofile->getP(), $real_proj . $ofile->getE() );

  copy ( $proj, $real_proj);
#  if ( $KeepScript !~ /YES/i and $TotalSteps eq $CurrentStep )
#  {
#   push ( @DeleteFileList, $real_proj);
#   push ( @DeleteFileList, $real_proj . '.user');
#  }
 }

 return;
}

#------------------------------------------------------------------
sub _modify_2005_sln
{
 my $sln = shift;
 open ( my $sfh, '<:utf8', $sln ) || return;
 my ( $tfh, $temp_sln ) = tempfile( 'omXXXXX', SUFFIX => '.sln', UNLINK => 0);
 close $tfh;
 open $tfh, '>:utf8', $temp_sln;
 push ( @DeleteFileList, $temp_sln ) unless $KeepScript =~ /YES/i;

 my $om_build_guid;
 local $_;
 while ( <$sfh>)
 {
  chomp;
  if ( m{^\s*Project\(} )
  {
   my @t = split m{,};
   my $line = $t[0];
   if ( $line =~ m{OMBuild"$} )
   {
    #Project("{F184B08F-C81C-45F6-A57F-5ABD9991F28F}") = "CalcOMBuild", "CalcOMBuild\CalcOMBuild.vbproj", "{A4A77ABD-A211-4BA0-AA95-4293FAEF8F6B}"
    $om_build_guid = $t[2];
    $om_build_guid =~ s{\s+}{}g;
    $om_build_guid =~ s{"}{}g;

    #-- keep looking for EndProject
    while ( <$sfh> )
    {
     if ( m{^\s*EndProject} )
     {
      $_ = <$sfh>;
      chomp;
      last;
     }
    }
   }

   #-- look for webprojects
   if ( $line =~ m{http://} )
   {
    #-- for now, we skip these builds, as we do not contact IIS
    while ( <$sfh> )
    {
     if ( m{^\s*EndProject} )
     {
      $_ = <$sfh>;
      chomp;
      last;
     }
    }
   }
  }
  next if ( $om_build_guid and m{$om_build_guid} ) ;
  print $tfh $_, "\n";
 }
 close $sfh;
 close $tfh;
 if ( -e $sln) #Added this to rename .sln file in cases where we build in the source directory
 {
  copy( $sln, $sln . '.omtemp' );
  push(@RenamedItems, $sln . '.omtemp' );
 }
 copy( $temp_sln, $sln );

 return ;
}

#------------------------------------------------------------------
sub fix_CmdLineTargets
{
 use File::Spec;

 my @tgts = @_;
 my @return_tgts;
 foreach my $t (@tgts )
 {
  if ( $t eq 'all' ) { next; }

  push @return_tgts, File::Spec->canonpath($t);
  #'C3CleanupScheduleMts/../Output/Release/C3CleanupScheduleMts.dll'
 }
 return @return_tgts;

}


