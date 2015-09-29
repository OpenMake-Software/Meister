#########################################
#-- Set script version number
#   Last updated:
#    JAG - 07.13.04 - make 'build.xml' more meaningful
my $ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Ant\040Javac\040Task.sc,v 1.21 2010/11/04 21:39:10 layne Exp $';
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
@PerlData = $NewerDeps->load("NewerDeps", @PerlData );
@PerlData = $TargetDeps->load("TargetDeps", @PerlData );
@PerlData = $TargetRelDeps->load("TargetRelDeps", @PerlData );
#  @PerlData = $TargetSrcDeps->load("TargetSrcDeps", @PerlData );

$StepDescription = "Javac Task for " . $Target->get;
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

#-- the following are files that might be specified
#   as an option, where the script will have to
#   parse out and substitute a file.
my %TaskOptionFiles = ();


#-- because we call javac.main directly, we have to translate Ant to javac attributes
#   that's what this hash does
#
#   The hash is set up to be keyed by Ant attribute. The value is an array.
#   The first array element is a bitmask indicating that either, both, or neither
#   of the Ant attribute or the javac command line takes a flag
#    1 = Ant takes option
#    2 = javac takes option
#   second element is the flag to pass to the javac compiler. if the compiler
#   takes an option, it gets added on directly behind the flag, so note the space.
#
#   In the case of Ant taking an option but javac not, (bitmask == 1 ), it's assumed
#   the Ant option is either "on|off"
#
#   Also, Destdir and compiler are special cases treated below.
#
#-- JAG - 04.29.04 - case 4610, added extra options
my %AntJavacOptions = ( "encoding" => [3, "-encoding "],
                        "nowarn" => [1, "-nowarn "],
                        #-- JAG - 05.09.05 - case 5707 - debug is now a special case
                        #"debug" => [1, "-g "],
                        #"debuglevel" => [ 3, "-g:"],
                        "optimize" => [1, "-O"],
                        "deprecation" => [ 1, "-deprecation"],
                        "verbose" => [1, "-verbose"],
                        "target"  => [3, "-target"],
                        "source"  => [3, "-source"],
                        "encoding" => [3, "-encoding"]
                     );

#-- set the name of the build.xml file. This is used
#   for debug purposes to have a more meaningful name
#   when the -ks option is used.
my $Build_XML_File = 'build_' . $Target->get;
$Build_XML_File =~ s|\W+|_|g;
$Build_XML_File .= '.xml';
push @dellist, $Build_XML_File unless $KeepScript =~ /y/i;
my $CompilerArgs .= ' -buildfile ' . $Build_XML_File;

#-- default options defined for Javac. These get
#   filled in below
my $ClassPath;
my $BootClassPath;
my $JikesClassPath;
my $SourcePath;
my $global_options;

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

#-- javac: if more than one option group, error out
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
my $destdir = $buildopt->getBuildTaskOption( "destdir", $build_task, $option_group);
if ( $destdir =~ /,/ )
{
 &omlogger("Begin",$StepDescription,"FAILED","$StepDescription failed!",$Compiler,$CompilerArgs,$LogOutputNL,$RC,@Output);
 $RC = 1;
 push(@CompilerOut, "ERROR: Multiple Destination directories specified via comma-separated list in destdir= \n" );
 push(@CompilerOut, "Reconfigure the target definition file to specify only one destdir= \n" );
 push @dellist, $Target->get;

 &omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), $RC = 1 if ($RC != 0);

 &ExitScript($RC);
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
#-- tools.jar is added to access the java compiler
#
#-- JAG - 04.23.07 - case FLS-4 Need to put int-dir + destdir on the classpath
$destdir =~ s{^"}{};
$destdir =~ s{"$}{};
my $intdir_dl = $IntDir->get . $DL;
$destdir = $intdir_dl . $destdir;
$destdir =~ s{\\}{/}g;

$ClassPath = $destdir . $PathDL . $ClassPath;
if ($^O =~ /darwin|osx/i)
{
$ClassPath .= $PathDL . $ENV{JAVA_HOME} . $DL . "../Classes" . $DL . "classes.jar";
}
else
{
$ClassPath .= $PathDL . $ENV{JAVA_HOME} . $DL . "lib" . $DL . "tools.jar";
}

push(@CompilerArguments,"arg value=\"-bootclasspath\" /\n");
push(@CompilerArguments,'arg value="' . $ClassPath . "\" /\n");

if ($compiler =~ /jikes/i)
{
 delete $ENV{JIKESPATH};
 push(@CompilerArguments,"arg value=\"-Djikes.class.path\" /\n");
 push(@CompilerArguments,'arg value="' . $ClassPath . "\" /\n");
}

if ($Verbose)
{
 $LogOutputNL .= "\nClassPath Directories:\n";
 $ClassPathNL = $ClassPath;
 $ClassPathNL =~ s/$PathDL/\n/g;
 $LogOutputNL .= $ClassPathNL;
}

#########################################
#-- Get sourcepath from VPath
#
push(@CompilerArguments,"arg value=\"-sourcepath\" /\n");
push(@CompilerArguments,'arg value="' . $VPath->getString("",$PathDL) . "\" /\n");

if ($Verbose)
{
 $LogOutputNL .= "\n\nSourcePath Directories:\n";
 $LogOutputNL .= $VPath->getString("","\n");
}

#########################################
#-- Get the options
#

#-- set the options. Because we only allow one task, we can do this
#   here. May change in future.
my @options = $buildopt->getBuildTaskOptions( $build_task, $option_group);

#-- JAG - 05.09.05 - case 5707 - need special handling of debug/debuglevel
#    to match Ant

my $options_str = join " ", @options;
#if ( $options_str =~ /debug="?on"?/ )
if ( $options_str =~ /debug="?on"?/ || $options_str =~ /debug="?true"?/ ) #-- JAG - 08.24.06 - case 7356
{
 #-- allowable is word, space, comma
 if ( $options_str =~ /debuglevel="([\w\s,]+?)"/ )
 {
  push(@CompilerArguments, "arg value=\"-g:$1\" /\n");
 }
 else
 {
  push(@CompilerArguments, "arg value=\"-g\" /\n");
 }
}
else
{
 # Default must be no -g flag for RELEASE builds (no '-g' flag is different than '-g:none')
 #  this broke backward compatibility - SAB
 
 # The code above should handle  -g:none if someone wants it
 
 # push(@CompilerArguments, "arg value=\"-g:none\" /\n");
}

#-- need to parse options for missing quotes

#-- Look to see if any options passed lead with -J -- indicating arguments
#   to pass to the underlying JVM
#
my $jvmargs = "";
foreach my $opt ( @options )
{
 next unless $opt;
  if ( $opt =~ /(\-*\w+)=?"?(\S+)/ )  #" #match param on non-whitespace characters instead of word characters due to version numbers such as 1.4 AG 1.7.05 5353
 {
  my $key = $1;
  my $val = $2;
  $val =~ s|"$||g; #"

  #-- see if this is a guy we need
  #-- JAG - case 4744. If an Option is precedeed by -J, this
  #   should get passed to the JVM.
  if ( $key =~ s/^-J// )
  {
   		$key .= "m" if($key =~ m{Xm[sx]([0-9]+)}); # we accidentally hack off the trailing "m" in the regex above
 		$key = "-" . $key if ($key !~ m{^\-+});
 		$jvmargs .= $key . " ";
  }
  elsif ( $AntJavacOptions{ $key} )
  {
   my ( $bitmask, $joption) = @{$AntJavacOptions{$key}};
   if ( $bitmask & 1 )
   {
    #-- mismatch in options
    next unless $val;

    #-- now see if javac takes an option
    if ( $bitmask & 2 )
    {
     $global_options = "$joption $val"; #AG 1.7.05 5353
    }
    else
    {
     #-- javac doesn't take an option, so look for "on" or "off"
     if ( $val =~ /on/i )
     {
      $global_options = "$joption"; #AG 1.7.05 5353
     }
    }

    #-- add to args
    my @opts = split " ", $global_options;
    foreach ( @opts )
    {
     push(@CompilerArguments, "arg value=\"" . $_ . "\" /\n");
    }
   }
  }
 }
}
$jvmargs =~ s{\s+$}{};

#########################################
#-- set the destdir and the compiler class
$compiler = $buildopt->getBuildTaskOption( "compiler", $build_task, $option_group);

#-- handle the compiler class
#-- default for Modern,javac1.3 or greater
my $CompilerClass = "com.sun.tools.javac.Main";
$CompilerClass = "sun.tools.javac.Main"
  if ($compiler =~ /javac1\.[12]/ || $compiler =~ /classic/i );
#-- tentative kopi support?
$CompilerClass = "at.dms.kjc.Main"
  if ($compiler =~ /kcj/ || $compiler =~ /kopi/i );

#-- JAG - 04.23.07 - case FLS-4
#my $wanted_dir = $buildopt->getBuildTaskOption( "destdir", $build_task, $option_group);
#$wanted_dir =~ s/^"//;
#$wanted_dir =~ s/"$//;
#
#my $intdir_dl = $IntDir->get . $DL;
#-- this needs
#$destdir = $intdir_dl . $wanted_dir;
#$destdir =~ s|\\|\/|g;

push(@CompilerArguments, "arg value=\"-d\" /\n");
push(@CompilerArguments, "arg value=\"$destdir\" /\n");

#-- make sure Destdir exists. Note mkfulldir takes a file, not a path
&mkfulldir($destdir . "/temp" ) unless ( -d $destdir);

#########################################
#-- determine packages based on the java
#  files in the .wsdljava .omidl, .copypkg
#  files and the java dependencies
#
@Newer = &GetPackageDeps($NewerDeps);
push(@Newer, $NewerDeps->getExt(qw(.java)));

#-- case 5026/7010 - SAB
if ( @Newer == () ) { #-- handle case where only classpath has changed
  if ( $NewerDeps->getList ) { #-- if there is something newer that is not java, e.g. .classpath
   @PerlData = $TargetDeps->load("TargetDeps", @PerlData );
   @Newer = $TargetDeps->getExt(qw(.java));
  }
}

if ( @Newer == () ) {
  $RC = 1;
  push(@CompilerOut, "ERROR: Ant Javac Task.sc: No source code was found to compile.\n", "\n");
  push @dellist, $Target->get;

  omlogger("Final",$StepDescription,"ERROR:","$StepDescription failed.",$Compiler,$CompilerArgs,$ClassPathNL,$RC,@CompilerOut);

  ExitScript $RC;
}

#########################################
#-- Remove old classes in package dirs
#
#-- This makes little sense. $Class gets set to "./C:/l..."
$LogOutputNL .= "\n\nOut of Date Files:\n";

foreach $file (sort @Newer)
{
 #$Class = $file;
 #$Class =~ s/\.java/\*\.class/;
 #$Class   = $IntDir->get . $DL . $Class;

 # escape $ in $file for ANT compilation
 if ($file =~ m{\$})
 {
  $escapefile = $file;
  $escapefile =~ s{\$}{\$\$}g;
  push(@FilesToCompile,'arg value="' . $escapefile . "\" /\n");
 }
 else
 {
 push(@FilesToCompile,'arg value="' . $file . "\" /\n");
 }

 $LogOutputNL .= "$file\n";

 #chmod 0777, "$Class";
 #unlink ($Class);
}

##################################################
# Determine if we have jvmargs
#
my $fork = "";
if ( $jvmargs )
{
 $fork = "fork=\"true\" \n";
 $jvmargs = "jvmarg value=\"$jvmargs\" /\n";
}

##################################################
# Write Build.xml
#
$xml =<<ENDXML;
project name = "$Project" default = "compile" basedir = "."

!-- Set properties --
!--   ignore the classpath that Ant is running under --
property name = "build.sysclasspath" value = "ignore" /

property name = "src" value = "$E{$IntDir->get}" /
property name = "build" value = "$E{$IntDir->get}" /

!-- Start compile section --
target  name = "compile"
java failonerror="true" classname="$CompilerClass" classpath = "$ClassPath" $fork
 $jvmargs

 @CompilerArguments
 @FilesToCompile

/java
/target

!-- End the project --
/project
ENDXML

######################################
# Execute Build
#
&omlogger("Begin",$StepDescription,"FAILED","$StepDescription succeeded.",$Compiler,$CompilerArgs,$LogOutputNL,$RC,@Output);

#-- JAG - 08.02.05 - case 6145 : if there are no files to compile, don't run 
#   Ant. This isn't needed if we were to use the 'Ant Javac' task directly.
if ( @FilesToCompile)
{
 &WriteAntXML($xml, $Build_XML_File );
 @CompilerOut = `$Compiler $CompilerArgs 2>&1`;
}
else
{
 @CompilerOut = ();
}
 
foreach $l (@CompilerOut) 
{ 
print $l;
 $l =~ s/\[java\]/\[javac\]/;
 $l =~ m/\[javac\][\s.](\d+)[\s.]error/;
 if ($1 ne "")
 {
  $RC += $1;
 }

 $l =~ m/\[javac\] Java Result: (\d+)/;
 if ($1 ne "")
 {
  $RC += $1;
 }
}

$RC += &Check4Errors("FAILED",$?,@CompilerOut);
$RC += &Check4Errors("Could not find",$?,@CompilerOut);
$RC += &Check4Errors("cannot resolve symbol",$?,@CompilerOut);
$RC += &Check4Errors("cannot find symbol",$?,@CompilerOut);

#-- stupid Ant prints "BUILD SUCCESSFUL" when it fails
#   because of not finding the compiler

@CompilerOut = grep !/BUILD SUCCESSFUL/, @CompilerOut
 if grep /Could not find/, @CompilerOut;

#-- JAG - 02.11.05 - see if we have any properties files
#                    these are absolute paths.
my @properties_files = $TargetDeps->getExt(qw(.properties));

#-- this gets classes from .javac filesS
@Classes = &GetPackageDeps($TargetRelDeps);

#-- JAG 02.06.04
#
#   This has to be reworked based on destdir/dir and the Java package structure.
#
#   The following occurs.
#   1. We pass to Ant the full file name
#    - C:/work/Minibank/MinibankWeb/Java Source/com/minibank/foo.java
#
#      we don't know the package structure starts at "com" because the
#      relative path is ($TargetRelDeps)
#
#    - MinibankWeb/Java Source/com/minibank/foo.java
#
#   2. We pass a destdir="intdir/classes" to Ant
#   3. Ant places the files in
#      <build dir>/intdir/classes/com/minibank/foo.java
#
#   We have to parse the "expected" classes "MinibankWeb/Java Source/com/minibank/*.java"
#   to find the class files in the build directory.
#
push(@Classes, $TargetRelDeps->getExt(qw(.java)));

#-- Use a subroutine
@Packages = &GetBuildDirPackages($destdir, @Classes);

open(FP, ">$TargetFile");
my $target_path = $Target->getDP;
#-- JAG - 05.21.04 - Case  4689 - wrong slash
#$target_path =~ s/\//\//g;
$target_path =~ s/\\/\//g;
my $etarget_path = quotemeta($target_path);

#-- make a hash of defined package locations.
#   we strip off $eTarget_path here

#-- JAG 10.18.06 - case 7509 - 'map' modifies the array in place, so use
#      a temp array
my @tmp_packages = @Packages;
my %package_hash = map { $_ =~ s/^$etarget_path//;
                         $_ =~ s/^\///;
                         $_ =~  s/^\\//;
                         $_ =~ s/\/$//;
                         $_ = "." if ($_ eq "");
                         $_ => 1 } @tmp_packages;
                         
#-- now look for properties that match to this
foreach my $properties_file ( @properties_files )
{
 $properties_file =~ s/\\/\//g;
 #-- split the path
 my @p = split /\//, $properties_file;
 my $file = pop @p;
 while ( @p)
 {
  my $path = join "/", @p;
  if ( $package_hash{$path})
  {
   #-- copy the file
   my $local_file = $path . "/$file";
   copy ( $properties_file, $local_file);
 
   #-- add to .javac
   if ( $path eq "." )
   {
    print FP $file, "\n";
   }  
   else
   {
    print FP $path, "/", $file, "\n";
   }
   last;
  }
  shift @p;
 }
}

foreach (@Packages)
{
 #-- JAG - 03.05.04 - strip off leading directory if the target lives in that
 #                    directory as well
 s/^$etarget_path//;
 s/^\///;
 s/^\\//;
 s/\/$//; #AG 11.12.04 Added to strip off end slash case 5277
 $_ = "." if ( $_ eq "" );
 if ($_ eq ".")
 {
  print FP "*.class\n";
 }
 else
 {
  print FP "$_$DL*.class\n";
 }
}
close FP;

&omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), $RC = 1 if ($RC != 0);
&omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut) if ($RC == 0);

