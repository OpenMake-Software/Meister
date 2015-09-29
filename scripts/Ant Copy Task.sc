my $ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Ant\040Copy\040Task.sc,v 1.3 2008/09/11 23:59:39 adam Exp $';
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
#  @PerlData = $TargetDeps->load("TargetDeps", @PerlData );
  @PerlData = $TargetRelDeps->load("TargetRelDeps", @PerlData );
#  @PerlData = $TargetSrcDeps->load("TargetSrcDeps", @PerlData );

#########################################
#-- Set script version number
#   Last updated:
#   AG - 01.20.05

#my $Script_Version = 1.5;

$StepDescription = "Copy Files for " . $Target->get;
$Verbose = "true" if $Quiet =~ /no/i;
$LogOutputNL = "";
my $cwd = &getcwd();
my @IncludesLines = ();
my @OverrideLines = ();

$ENV{PATH} = $ENV{JAVA_HOME} . $DL . "bin" . $PathDL . $ENV{PATH} if ($ENV{JAVA_HOME});

#########################################
#-- Define global variables
#
#-- get the name from the $BuildTask variable
my $Ant_Type = "copy";
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

##########################################
#
#-- each Option Group is represents a different set of Copy commands.  Can copy groups to different todir destinations AG 01\21\05
#-- each Option Group is a separate fileset
#
my $i = 0;
my @TargetCopyLines = ();
foreach my $option_group ( @option_groups )
{
 #-- get the copy to directory
 my $to_dir = $buildopt->getBuildTaskOption( "todir", $build_task, $option_group);
 $to_dir =~ s/^"//;
 $to_dir =~ s/"$//;
 
 #-- the "dir" key represents the relative copy from location (relative to location in search path)
 #
 my $wanted_dir = $buildopt->getBuildTaskOption( "dir", $build_task, $option_group);
 $wanted_dir =~ s/^"//;
 $wanted_dir =~ s/"$//;

 my @wanted_dirs = ();
 my %wanted_file_hash = ();
 my @derived_deps = ();
 my @subtask_deps = ();

 #-- get the files in localres that correspond to this option group
 #-- JAG 04.14.04 - remove 1 as last option -- was returning all files (not
 #                  just files for this build)
 my ($file_ref, $opt_ref ) = $buildopt->getBuildTaskFiles( $build_task, $option_group );
 my @option_files = @{$file_ref};


 #-- remove files that are keyed in %TaskOptionFiles
 my $i = 0;
 my @t_option_files = @option_files;
 foreach my $file ( @option_files )
 {
  foreach my $val ( values %TaskOptionFiles )
  {
   #-- assume lowercase is okay here
   if ( lc $val eq (lc $file) )
   {
    splice( @t_option_files, $i, 1);
    $i--;
   }
  }
  $i++;
 }
 @option_files = @t_option_files;

 foreach (@option_files) { $_ =~ s|\\|/|g; } #all slashes forward
 next unless ( @option_files );

 $int_dir = $IntDir->getAbsolute;

 my $option_target = Openmake::FileList->new(@option_files);
 %wanted_file_hash = &AntFilesetOrg($option_target,$TargetRelDeps,$wanted_dir,$int_dir,"",qw( .javag .javac .classpath .wsdljava .rmic ));

 push @wanted_dirs, (keys %wanted_file_hash);
 
 #-- get the options at the Option Group. Ignore the "Default case", since
 #   this will be handled at the root level
 my @options = grep({$_ !~ /todir/i && $_ !~ /dir/i} $buildopt->getBuildTaskOptions( $build_task, $option_group));
 my $extraOptions = "";
    
 #-- need to parse options for missing quotes and add to options string
 foreach my $opt ( @options )
 {
  unless ( $opt =~ /="/ && $opt =~ /"$/) #-- if it's quoted, it's fine.
  {
   $opt =~ s|=|="|; #"
   $opt .= "\"";
  }
  $extraOptions .= " $opt";
 }
 
 push(@IncludesLines, "copy todir=\"$to_dir\"$extraOptions\n");
 #-- strip off the leading ${dir} off the resources if necessary
 #   this replaces module dir.
 foreach my $temp_dir ( @wanted_dirs )
 {
  if ( $temp_dir)
  {
   $temp_dir =~ s|^"||; #"
   $temp_dir =~ s|"$||; #"

   $temp_dir =~ s|^\.[\\\/]||;

   my $etemp_dir = $temp_dir;
   $etemp_dir =~ s|\\|\/|g;
   $etemp_dir =~ s|\/|\\\/|g;
  }

  #-- write build.xml lines for resources
  if (@{$wanted_file_hash{$temp_dir}} )
  {   
   if ($Quiet =~ /no/i)
   {
    $LogOutputNL .= "\nCopying to $to_dir from Directory:";
    $LogOutputNL .= "\n$temp_dir\n\n";
    foreach (@{$wanted_file_hash{$temp_dir}}) { $LogOutputNL .= " $_\n"; }
   }
   foreach (@{$wanted_file_hash{$temp_dir}}) 
   {
    my $copyToFile = $to_dir . $DL . "$_\n";
    $copyToFile =~ s|\\|/|g;
    push (@TargetCopyLines, $copyToFile);
   }
   push(@IncludesLines, "fileset dir=\"$temp_dir\"\n");
   push(@IncludesLines, GetAntIncludeXML( @{$wanted_file_hash{$temp_dir}} ) );
   push(@IncludesLines, "/fileset\n");
   $uses_fileset = 1;
  }
 } #-- end of loop on possible separate directories in the dir= parameter
 push(@IncludesLines, "/copy\n");
} #-- end of zipfilesets.



my $xml =<<ENDXML;
project name = "$Project" default = "$Ant_Type" basedir = "."

!-- Set properties --
!--   ignore the classpath that Ant is running under --
property name = "build.sysclasspath" value = "ignore" /

!-- Start copy section --
target name = "$Ant_Type"
 @IncludesLines
/target

!-- End the project --
/project
ENDXML
WriteAntXML($xml, $Build_XML_File);

# Execute ant
# -----------
omlogger("Begin",$StepDescription,"FAILED","$StepDescription succeeded.",$Compiler,$CompilerArgs,$LogOutputNL,$RC,@Output);
@CompilerOut = `$Compiler $CompilerArgs 2>&1`;
$RC = Check4Errors("FAILED",$?,@CompilerOut);
omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut) if ($RC == 0);

#Close out Target Log file with summary of results
if ($RC == 0)
{
 #Open Target file to write copy output
 $TargetFile = $Target->get;
 unless ( open(TARGET, ">$E{$TargetFile}") )
 {
  $StepDescription = "ERROR: Couldn't open $E{$TargetFile}.";
  omlogger('Final',$StepDescription,"FAILED",,"","","",1,"");
 }
 print TARGET @TargetCopyLines;
 close TARGET;
}
