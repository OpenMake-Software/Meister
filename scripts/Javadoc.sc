$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Javadoc.sc,v 1.8 2009/06/18 16:28:56 sean Exp $';
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

# Check that target is named 'index.html'
#$File = $Target->getFE;
#unless ( $File =~ /^index\.html$/i) {
#$RC=1;
# omlogger("Final",$StepDescription,"ERROR:","ERROR: Target must be named '[directory/]index.html'\n",'Openmake Javadoc.sc','','',$RC,());
# ExitScript $RC;
#}
####### Setting Compiler Choice and Search Behavior #######

@CompilersPassedInFlags = ("javadoc");
$DefaultCompiler  = "javadoc";

#-- Find the compiler
#($Compiler,$Flags) = GetCompiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);
$Compiler = FirstFoundInPath( $DefaultCompiler );
$Compiler =~ s/\"//g; #"
if ( $Compiler eq "" )
{
 $RC                      = 1;
 $main::Quiet             = "NO";
 @main::CompilerOut       = ( "Compiler <$DefaultCompiler> was not found");
 $main::Compiler          = "<not found>";
 $main::StepDescription   = "Extracting compiler name from flags passed";
 $main::StepError         = "$StepDescription failed!";
 omlogger( "Final", $StepDescription, "ERROR:", "ERROR: $StepDescription failed!", $Compiler, $CompilerArguments, "", $RC, @main::CompilerOut );
 ExitScript( $RC, @main::DeleteFileList );
} #-- End: if ( $CompilerFound eq...

#########################################
#-- create the option objects
#
use Openmake::BuildOption;
my $optionref;
( $CFG eq "DEBUG") ? $optionref = $DebugFlags : $optionref = $ReleaseFlags;
my $buildopt = Openmake::BuildOption->new($optionref);

#-- Get Classpath from dependency
$ClassPath = GetClasspath( $TargetDeps );

#-- Define the SourcePath
# LN - 6/5/2009: Only use directories where found source is located
# $SourcePath = $VPath->getQuoted;

#-- Derive the output directory this should be
# created already from the header
$OutputDir = $Target->getPath;

#-- Get the list of Java file dependencies
@JavaFiles = $TargetRelDeps->getExt(qw(.java));

@Packages = GetPackages(@JavaFiles);  # Return the package in the com/openmake/server/* form.

#-- Get sourcepath for Java file dependencies
@JavaFiles_Abs = $TargetDeps->getExt(qw(.java));
@sourceDirs = ('.');
foreach $dir ($VPath->get) {
  @foundSource = grep(/^\Q$dir\E/, @JavaFiles_Abs);
  $nFound = @foundSource;
  push (@sourceDirs, $dir) if ($nFound);
}

$SourceSP = Openmake::SearchPath->new(@sourceDirs);
$SourcePath = $SourceSP->getQuoted;

#-- deal with the options in certain order
my @options    = $buildopt->getBuildTaskOptions( $BuildTask, $OPTIONGROUP_DEFAULT_NAME, 1 );

#-- put locale first
my $i = 1; #-- this will get the next value after "locale"
my $locale_val;
foreach my $opt ( @options )
{
 if ( $opt eq '-locale' )
 {
  $locale_val = $options[$i];
 }
 if ( $opt eq '-basedir' )
  {
   $basedir_val = $options[$i];
 }
 $i++;
}

my $locale_str = "";
if ( $locale_val )
{
 #-- remove from the list
 @options = grep { $_ ne '-locale' } @options;
 @options = grep { $_ ne $locale_val } @options;
 $locale_str = "-locale $locale_val";
}
if ( $basedir_val )
{
 #-- remove from the list
 @options = grep { $_ ne '-basedir' } @options;
 @options = grep { $_ ne $basedir_val } @options;

 # Specify basedir with "standard" and system delimiters
 $basedir_val =~ s/\\/\//g;
 
 $basedir_val_DL = $basedir_val;
 $basedir_val_DL =~ s/\//$DL/g;

 # Add VPath entries for $basedir_val
 my @dirs = $SourceSP->get;
 @dirs_with_basedir = ();
 foreach my $dir (@dirs) {
   push(@dirs_with_basedir, $dir);
   push(@dirs_with_basedir, $dir . $DL . $basedir_val_DL);
 }

 $BaseDirSP = Openmake::SearchPath->new(@dirs_with_basedir);
 $SourcePath = $BaseDirSP->getQuoted;
}
my $option_str = join( " ", @options);

#-- get group options
my ( $file_ref, $opt_ref);
( $file_ref, $opt_ref) = $buildopt->getBuildTaskFiles( $BuildTask );

my %groups;
$i = 0;
foreach my $file ( @{$file_ref} )
{
 #-- see if this has a group tag
 if ( $opt_ref->[$i] =~ /\-group\s+(.+?)\s+\-/ )
 {
  #-- get the group.
  my $group = $1;

  my $rel_file = $file;
  $rel_file =~ s/\\/\//g;
  #-- need to match against Source Path
  my @dirs  = $VPath->get;
  foreach my $dir ( $VPath->get )
  {
   next if ( $dir eq "." );
   $dir =~ s/\\/\//g;
   my $index =  index(  $rel_file, $dir );
   if ( $index >=0 )
   {
    $rel_file = substr( $rel_file, $index+length($dir)+1 );
    last;
   }
  }

  my @packages = GetPackages( $rel_file);
  my $package = $packages[0];
  
  # Filter basedir value
  $package =~ s/\\/\//g;
  $package =~ s/^$basedir_val//;
  $package =~ s/^\/*//;
  
  $package =~ s/\\/\./g;  # Change the \ to .
  $package =~ s/\//\./g;  # Change the / to .
  $package =~ s/\*//g;    # Remove the *

  $groups{$package} = $group;

 }
 $i++;
}

#-- Handle whether or not to delete temporary files
$unlink = 1;

#-- Do not delete temporary response file if -ks flag is passed to om
$unlink = 0 if $KeepScript eq 'YES';

#-- Generate temporary file

($tmpfhs,$FPSource) = tempfile('omjdXXXX', SUFFIX => '.txt', UNLINK => $unlink );

my $group_flags = "";
#-- Make sure syntax is acceptable to Javadoc
foreach $package (@Packages)
{
 # Filter basedir value
 $package =~ s/\\/\//g;
 $package =~ s/^$basedir_val//;
 $package =~ s/^\/*//;
 
 $package =~ s/\\/\./g;  # Change the \ to .
 $package =~ s/\//\./g;  # Change the / to .
 $package =~ s/\*//g;    # Remove the *

 if ( $groups{$package} )
 {
  my $package_file = $package;
  $package_file .= "*";
  $group_flags .= "-group $groups{$package} \"$package_file\" ";
 }
 print $tmpfhs "$package\n";
}

#-- An alternate method that generates different docs
#  reserved for possible future openmake flag
#foreach $file (@JavaFiles ) { print $tmpfhs "$file\n" }

close $tmpfhs;

#-- Construct arguments
# LN 6/5/2009 - ClassPath will be displayed in output but will actually be referenced
#               using an Environment Variable to avoid hitting Windows Command Line Limit
$ENV{CLASSPATH} = $ClassPath if $ClassPath;

$CompilerArguments       = "$locale_str $Defines";
$CompilerArgumentsOutput = "$locale_str $Defines";

# $CompilerArguments     .= " -classpath $ClassPath" if $ClassPath;
$CompilerArgumentsOutput .= " -classpath $ClassPath" if $ClassPath;

$CompilerArguments       .= " -sourcepath $SourcePath $option_str -d $OutputDir";
$CompilerArgumentsOutput .= " -sourcepath $SourcePath $option_str -d $OutputDir";

if ( $group_flags )
{
 $CompilerArguments       .= " " . $group_flags;
 $CompilerArgumentsOutput .= " " . $group_flags;
}
$CompilerArguments       .= " \@$FPSource";
$CompilerArgumentsOutput .= " \@$FPSource";


if ( $group_flags )
{
 $CompilerArguments .= " " . $group_flags;
}
$CompilerArguments .= " \@$FPSource";

#-- Begin logging for the Javadoc command
$StepDescription   = "Performing Javadoc Compile";
omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArgumentsOutput,'',$RC,@CompilerOut);

#-- Execute the command
@CompilerOut = `$Compiler $CompilerArguments 2>&1`;
$RC = $?;

#-- Copy the index.html file to the target name
#   Check  if the final target is a .jup file
my $target = $Target->getDPFE;
if ( $target !~ /\.jup$/ )
{
 copy ( $IntDir->get . "/index.html", $Target->getDPFE);
 $RC = $?;
}

#-- scan the output to see what we created. 
#   use the .jup (jar up) extensions
my $javadoc_target = $Target->getDPF . ".jup";
open ( OUT, '>', $javadoc_target);
my $intdir = $IntDir->get;
$intdir =~ s/\\/\//g;
foreach ( @CompilerOut)
{
 #Generating javadoc\bingo\shared\GameListenerThread.html
 if ( /\s*Generating\s+(.+?)\.\.\.\s*$/ )
 {
  #-- remove intdir
  my $file = $1;
  $file =~ s/\\/\//g;
  $file =~ s|^$intdir[\\\/]||;
  print OUT $file, "\n";
 }
}
#-- JAG - 02.23.05 - if resources were generated, they appear not to be listed
#   in the compiler output (eg. 'resource/inherit.gif'). THis is JDK 1.4.2 and
#   later
my $res_dir;
$res_dir .= "$intdir/" if ( $intdir );
$res_dir .= "resources";
if ( opendir ( RES, $res_dir ) )
{
 my @res_files = grep { $_ !~ /^\./ && -f "$intdir/resources/$_" } readdir(RES);
 close RES;
 foreach my $res_file ( @res_files )
 {
  print OUT "resources/", $res_file, "\n";
 }
}
close OUT;


#if($!)
#{
# omlogger("Final",$StepDescription,"ERROR:","ERROR: Unable to copy \"index.html\" to the Target name.'\n",'Openmake Javadoc.sc','','',$RC,());
#}

#-- Report the outcome
omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArgumentsOutput,'',$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArgumentsOutput,'',$RC,@CompilerOut) if ($RC == 0);
