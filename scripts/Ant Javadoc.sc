$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Ant\040Javadoc.sc,v 1.1 2006/02/07 16:59:46 adam Exp $';
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

$StepDescription = "Javadoc Task for " . $Target->get;
$TargetFile = $Target->get;
$Verbose = $Quiet =~ /no/i;
$LogOutputNL = "";
my $cwd = &getcwd();
my @IncludesLines = ();

$ENV{PATH} = $ENV{JAVA_HOME} . $DL . "bin" . $PathDL . $ENV{PATH} if ($ENV{JAVA_HOME});

#########################################
#-- Define global variables
#
#-- get the name from the $BuildTask variable
my $Ant_Type = "javac";
if ( $BuildTask =~ /Ant\s+.?\s+(\w+)$/ )
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
$optionref    = $DebugFlags if ( $CFG eq "DEBUG" );

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
my $option_group ;

#-- javadoc: if more than one option group, error out
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

#-- if more than one Destdir specified (through a comma-separated list), error out.
#   This could have occurred in the old-style 6.2 targets. Print error.
#
my $destdir = $IntDir->get;

#-- find the Ant compiler
$Compiler = &GetAnt();

#########################################
#-- Get the options
#

#-- set the options. Because we only allow one task, we can do this
#   here. May change in future.
my @options = $buildopt->getBuildTaskOptions( $build_task, $option_group);
for ($i = 0; $i < @options; $i++)
{
 if ($options[$i] =~ /^<.*>$/) #assume if it starts and ends with < and > signs that it is a nested attribute
 {
  push @nestedAttribs, $options[$i] . "\n";
 }
 elsif ($options[$i] =~ /^</)
 {
  $splitNestedAttrib = $options[$i];
  $i++;
  for ($n = $i; $n < @options; $n++)
  {
   if ($options[$n] =~ />$/)
   {
    $splitNestedAttrib .= " $options[$n]";
    $i = $n + 1;
    last;
   }
   else
   {
    $splitNestedAttrib .= " $options[$n]";
    $n++;
   }  
  }
 }
 else
 {
  push @javadocOpts, $options[$i];
 }
}
$javadocOptions = join " ", @javadocOpts;
#########################################
#-- determine packages based on the java
#  files and the java dependencies
#
@Newer = &GetPackageDeps($NewerDeps);
push(@Newer, $TargetDeps->getExt(qw(.java)));
push(@Newer, $TargetDeps->getExt(qw(.class)));

my $option_target = Openmake::FileList->new(@Newer);
%wanted_file_hash = &AntFilesetOrg($option_target,$TargetRelDeps,"",$destdir,"",qw(.javac .classpath ));

push @wanted_dirs, (keys %wanted_file_hash);

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
  push(@FilesToCompile,'fileset dir="' . $temp_dir . "\"\n");
  $LogOutputNL .= "\nPerforming Javadoc from directory $temp_dir:\n\n";
 }

  #-- write build.xml lines for resources
 if (@{$wanted_file_hash{$temp_dir}} )
 {
  foreach $file(@{$wanted_file_hash{$temp_dir}})
  {
   push(@FilesToCompile,'include name="' . $file . "\" /\n");
   $LogOutputNL .= "$file\n";
  }
 }
 push(@FilesToCompile,'/fileset' . "\n");
}


##################################################
# Write Build.xml
#
$xml =<<ENDXML;
project name = "$Project" default = "javadoc"

!-- Start javdoc section --
target  name = "javadoc"
javadoc destdir = "$destdir" $javadocOptions

@FilesToCompile
@nestedAttribs

/javadoc
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
$RC = $?;

&omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), $RC = 1 if ($RC != 0);
&omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut) if ($RC == 0);
