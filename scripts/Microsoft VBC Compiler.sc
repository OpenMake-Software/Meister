$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Microsoft\040VBC\040Compiler.sc,v 1.5 2005/11/08 19:20:37 adam Exp $';
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

###########################################################
# This script directly calls the vbc.exe compiler using
# files listed as dependencies
#
# There are different scripts to build vb from a solution
# or from a project file
#
###########################################################
#-- Load Openmake Variables from Data File
#   Uncomment Openmake objects that need to be loaded
#
#  @PerlData = $AllDeps->load("AllDeps", @PerlData );
#  @PerlData = $RelDeps->load("RelDeps", @PerlData );
#  @PerlData = $NewerDeps->load("NewerDeps", @PerlData );

@PerlData = $TargetDeps->load("TargetDeps", @PerlData );

#  @PerlData = $TargetRelDeps->load("TargetRelDeps", @PerlData );
#  @PerlData = $TargetSrcDeps->load("TargetSrcDeps", @PerlData );
#
####### Setting Compiler Choice and Search Behavior #######

@CompilersPassedInFlags = ("vbc");
$DefaultCompiler  = "vbc";

($Compiler,$Flags) = GetCompiler('','',$DefaultCompiler,@CompilersPassedInFlags);

#-- Handle whether or not to delete temporary files
$unlink = 1;

#-- Do not delete temporary response file if -ks flag is passed to om
$unlink = 0 if $KeepScript eq 'YES';

#-- Open temporary file to pass arguments to the linker
($tmpfh, $rsp) = tempfile('omXXXXX', SUFFIX => '.rsp', UNLINK => $unlink );

#-- Determine target type based on final target
#  extension and if the name of the build type
#  has the keyword 'windows' in it

if ( $Target->getE =~ /\.exe/i ) {
 if ( $BuildType =~ /windows/i ) { #-- assume this is a windows exe
  $TargetType = 'winexe';

 } else { #-- assume this is a console exe
  $TargetType = 'exe';
 }
} elsif ( $Target->getE =~ /\.dll/i ) {
 $TargetType = 'library';

} else {
 $TargetType = 'module';

}

#-- Get source files from om
$TargetFile = $Target->get;
@VBSource = $TargetDeps->getExtList('.vb');
@ImportModules = $TargetDeps->getExtList('.netmodule');

@IcoSource = $TargetDeps->getExt('.ico');
$IcoSourceFile = shift @IcoSource;

@References = $TargetDeps->getExtList('.dll');

#-- Create build option object
my $build_options;

use Openmake::BuildOption;

$build_options = Openmake::BuildOption->new($ReleaseFlags) if $CFG eq 'RELEASE';
$build_options = Openmake::BuildOption->new($DebugFlags) if $CFG eq 'DEBUG';

#########################################
#-- determine how many Build tasks/Option Groups we have
my @build_tasks = $build_options->getBuildTasks;

my $build_task = $build_tasks[0];

#-- find the build task that matches to the task
$build_task = $BuildTask if ( grep /$BuildTask/, @build_tasks );

#-- find the optiongroups;
my @option_groups = $build_options->getOptionGroups($build_task);

#########################################

print $tmpfh "/out:$TargetFile\n";
print $tmpfh "/target:$TargetType\n";
print $tmpfh "/verbose\n" if $Quiet eq 'NO';

#-- Handle Libpaths
foreach my $libpath ( $VPath->getList ) {
 print $tmpfh "/libpath:\"$libpath\"\n";
}

#-- Handle references

foreach my $reference ( @References ) {
 print $tmpfh "/reference:\"$reference\"\n";
}

#-- Handle imports

foreach my $import ( @ImportModules ) {
 print $tmpfh "/addmodule:\"$import\"\n";
}

print $tmpfh "/win32icon:$IcoSourceFile\n" if $IcoSourceFile ne '';

#-- Get global imports by scanning the corresponding project file 6380 12.8.05 ADG
$VBProjSource = $TargetDeps->getExt(qw(.VBPROJ));
if ($VBProjSource)
{
 open VBPROJ, "$VBProjSource";
  my @VBProjLines = <VBPROJ>;
 close VBPROJ;
 my $ImportLine = "/imports:";
 foreach (@VBProjLines)
 {
  if ($_ =~ /.*\<Import Namespace = (.*)\/\>/)
  {
   my $import = $1;
   $import =~ s/^\s+//;
   $import =~ s/\s+$//;
   push @Imports, $import;  
  }
 }
 $ImportArgs = join "\,", @Imports;
 $ImportLine .= $ImportArgs;
 print $tmpfh "$ImportLine\n";
}

if ( @{file_ref} and $TargetType eq 'module' ) {
 #-- error, incompatible list of options
 $RC=1;
}

#-- Handle resources: we have three types, therefore there
#   must be three option groups

#-- Handle .Net Resources
my ( $file_ref, $option_ref )
 = $build_options->getBuildTaskFiles( "Compile", "Managed Resources" );

#-- since there is one main flag, we hardcode it here
foreach my $resource ( @{$file_ref} ) {
 print $tmpfh "/resource:\"$resource\"\n";
}

#-- Handle Win32 Resources
( $file_ref, $option_ref )
 = $build_options->getBuildTaskFiles( "Compile", "Win32 Resources" );

#-- since there is one main flag, we hardcode it here
foreach my $resource ( @{$file_ref} ) {
 print $tmpfh "/win32res:\"$resource\"\n";
}

#-- Handle Linked Resources
( $file_ref, $option_ref )
 = $build_options->getBuildTaskFiles( "Compile", "Linked Resources" );

#-- since there is one main flag, we hardcode it here
foreach my $resource ( @{$file_ref} ) {
 print $tmpfh "/linkresource:\"$resource\"\n";
}

#-- finally write out the .vb files

foreach my $vbfile ( @VBSource ) {
 print $tmpfh "\"$vbfile\"\n";
}

close $tmpfh;

$LibPathNL = '';

if ( $Quiet eq 'NO' ) {
 open VBRSP, "<$rsp";
 my @lines = <VBRSP>;
 close VBRSP;

 $LibPathNL = join '',
  "\n",
  "********** BEGIN RSP FILE **********\n",
  @lines,
  "********** END RSP FILE **********\n";
}

$StepDescription = "Performing Visual Basic Compile (vbc.exe) for $TargetFile";

$CompilerArguments = "\@$rsp 2>&1";

omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$LibPathNL,$RC,@CompilerOut);

unless ( $RC ) {
 @CompilerOut = `$Compiler \@$rsp 2>&1`;
 $RC = $?;

 $StepError = "$StepDescription failed!";

} else {

 $StepError = "Incompatible options.  Can't use /linkresource for target type 'module'";
}

omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut), push(@DeleteFileList,$rsp) if ($RC == 0);
