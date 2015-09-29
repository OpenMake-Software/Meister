#########################################
#-- Set script version number
#   Last updated:
#    JAG - 07.13.04 - make 'build.xml' more meaningful
my $ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Ant\040Javah\040Task.sc,v 1.3 2005/05/16 16:16:37 jim Exp $';
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

#  @PerlData = $AllDeps->load("AllDeps", @PerlData );
#  @PerlData = $RelDeps->load("RelDeps", @PerlData );
#  @PerlData = $NewerDeps->load("NewerDeps", @PerlData );
@PerlData = $TargetDeps->load("TargetDeps", @PerlData );
@PerlData = $TargetRelDeps->load("TargetRelDeps", @PerlData );
#  @PerlData = $TargetSrcDeps->load("TargetSrcDeps", @PerlData );

$StepDescription = "Javah File Creation for " . $Target->get;
$Verbose = $Quiet =~ /no/i;
$LogOutputNL = "";
my $cwd = &getcwd();
my @IncludesLines = ();

#########################################
#-- Define global variables
#
#-- get the name from the $BuildTask variable
my $Ant_Type = "javah";
my $Task = "";
if ( $BuildTask =~ /Ant.?\s+(\w+)$/ )
{
 $Ant_Type = $1;
 $Ant_Type = lc($Ant_Type);
}

#-- set the name of the build.xml file. This is used
#   for debug purposes to have a more meaningful name 
#   when the -ks option is used.
my $Build_XML_File = 'build_' . $Target->get;
$Build_XML_File =~ s|\W+|_|g;
$Build_XML_File .= '.xml';
push @dellist, $Build_XML_File unless $KeepScript =~ /y/i;
my $CompilerArgs .= ' -buildfile ' . $Build_XML_File;

#########################################
#-- determine the configuration
#
my $optionref = $ReleaseFlags;
$optionref = $DebugFlags if ( $CFG eq "DEBUG" );

#########################################
#-- create the option objects
#
use Openmake::BuildOption;
my $buildopt = Openmake::BuildOption->new($optionref);

#########################################
#-- determine how many Build tasks/Option Groups we have
my @build_tasks = $buildopt->getBuildTasks;
my $build_task = $build_tasks[0];

#-- find the build task that matches to the task
$build_task = $BuildTask if ( grep /$BuildTask/, @build_tasks );

#-- find the optiongroups;
my @option_groups = $buildopt->getOptionGroups($build_task);

#-- find the compiler
$Compiler = &GetAnt();

#########################################
#-- Generate Bill of Materials if Requested
#
&GenerateBillofMat($BillOfMaterialFile->get,$BillOfMaterialRpt,$Target->get) if( defined $BillOfMaterialFile );

#########################################
# Special Options section
#
#-- the following is a loop over possible keys in the task argument
#   that we might want to find in the Build Task general options
#   For example, in Ant Jar, we look for Manifest.mf, web.xml
#   in the list of dependencies
#
#   We define a hash %TaskOptionFiles (at top of script)
#   to tell us what to look for
#   Note that the default type (eg. war) is defined from the
#   build type
#

##########################################
#
#-- Set the IntDir

$IntDirDL = $IntDir->get . "/";

##########################################
#
#-- Determine the overall basedir

my $BaseDir = $buildopt->getBuildTaskOption( "basedir", $build_task, $OPTIONGROUP_DEFAULT_NAME );
$BaseDir =~ s/^"//; #"
$BaseDir =~ s/"$//; #"

#-- Create the BaseDir with the Intermediate Directory appended as well
my $IntBaseDir = $BaseDir;
if($IntDirDL ne './' && $IntDirDL ne '/')
{
 $IntBaseDir = $IntDirDL . $BaseDir;
}

##########################################
#
#-- There should be only one Option Group
#
if ( scalar (@option_groups ) > 1 )
{
 &omlogger("Begin",$StepDescription,"FAILED","$StepDescription failed!",$Compiler,$CompilerArgs,$LogOutputNL,$RC,@Output);
 $RC = 1;
 push(@CompilerOut, "ERROR: More than one Option Group defined\n", "\n");
 push @dellist, $Target->get;

 &omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), $RC = 1 if ($RC != 0);

 &ExitScript($RC);
}
else
{
 $option_group = $option_groups[0];
}
#-- find the Ant compiler
$Compiler = &GetAnt();

#-- determine how we expect to call the compiler
my $compiler = $buildopt->getBuildTaskOption( "compiler", $build_task, $option_group);

#########################################
#-- Get classpath from Classpath Task
#
$ClassPath = GetClasspath( $TargetDeps );

#########################################
#-- Start prepping for the compile
#
#-- get the files in localres that correspond to this option group
my ($file_ref, $opt_ref ) = $buildopt->getBuildTaskFiles( $build_task, $option_group, 0);
my @option_files  = @{$file_ref};
my $option_target = Openmake::FileList->new(@option_files);

#-- strip the leading "basedir" from the dependency classes. 
#   This allows us to find the classes in the jar files
#
#-- get the relative parts of the files from the full paths
my %dir_hash = CommonPath( $option_target, $TargetRelDeps);
my @rel_option_files = ();
foreach my $k ( keys %dir_hash )
{
 push @rel_option_files, @{$dir_hash{$k}};
}
my $rel_option_target = Openmake::FileList->new(@rel_option_files);

my $OMBaseDir = Openmake::SearchPath->newFromScalar($BaseDir);

$rel_option_target = &StripModuleDirectories( $OMBaseDir, $rel_option_target );
@localres = CopyLocal($TargetDeps, $rel_option_target, $IntDirDL, qw(.CLASS));
push @localres, $rel_option_target->getExt( qw(.class));
@localres = &unique(@localres);

if ($Quiet =~ /no/i)
{
 $LogOutputNL .= "Adding to $Ant_Type:\n\n";
 foreach (@localres) { $LogOutputNL .= " $_\n"; }
}

#-- get the options at the Option Group.
my @options = $buildopt->getBuildTaskOptions( $build_task, $option_group);

#-- need to parse options for missing quotes
foreach my $opt ( @options )
{
 next unless $opt;
 
 #-- ignore passed in classpath
 if ($opt =~ /classpath=/ )
 {
  $opt = "";
  next;
 }
  
 #-- ignore passed-in destdir, we use output file
 if ($opt =~ /destdir=/ )
 {
  $opt = "";
  next;
 }
 
 #-- ignore passed-in basedir, we've already used it
 if ($opt =~ /basedir=/ )
 {
  $opt = "";
  next;
 }
 
 #-- following gets rid of ill-defined options like manifest=
 if ( $opt =~ /=(")?$/ ) #"
 {
  $opt = "";
  next;
 }
 next if ( $opt =~ /="/ && $opt =~ /"$/); #-- if it's quoted, it's fine.
 $opt =~ s|=|="|; #"
 $opt .= "\"";
}

#-- add our pieces to the options puzzle
#   1. OutputFile
#   2. Classpath

unshift @options, "classpath=\"$ClassPath\"";
unshift @options, "outputFile=\"" . $Target->get . "\"";

$options = join " ", @options;
$options =~ s|\s+| |g;
 
#-- create the javah task, with the @Localres classes as
#   nodes
push(@IncludesLines, "javah $options\n");
foreach my $class ( @localres )
{
 $class =~ s/\\/./g;
 $class =~ s/\.class$//;
 push(@IncludesLines, "class name=\"$class\" /\n" );
}
push(@IncludesLines, "/javah \n"); 

##################################################
# Write Build.xml
#
my $xml =<<ENDXML;
project name = "$Project" default = "$Ant_Type" basedir= "."

 !-- Set properties --
 !--   ignore the classpath that Ant is running under --
 property name = "build.sysclasspath" value = "ignore" /

 property name = "$Ant_Type" value = "." /

 !-- Start $Ant_Type section --
  target name = "$Ant_Type"

   @IncludesLines

 /target

!-- End the project --
/project
ENDXML

&WriteAntXML($xml, $Build_XML_File );

######################################
# Execute Build
#
&omlogger("Begin",$StepDescription,"FAILED","$StepDescription succeeded.",$Compiler,$CompilerArgs,$LogOutputNL,$RC,@Output);

@CompilerOut = `$Compiler $CompilerArgs 2>&1`;

$RC = Check4Errors("FAILED",$?,@CompilerOut);

&omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), $RC = 1 if ($RC != 0);
&omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut) if ($RC == 0);

&ExitScript($RC);
