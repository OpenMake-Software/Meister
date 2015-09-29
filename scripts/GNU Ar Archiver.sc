$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/GNU\040Ar\040Archiver.sc,v 1.3 2005/06/06 16:18:05 jim Exp $';
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
#  @PerlData = $TargetRelDeps->load("TargetRelDeps", @PerlData );
#  @PerlData = $TargetSrcDeps->load("TargetSrcDeps", @PerlData );

####### Setting Compiler Choice and Search Behavior #######

@CompilersPassedInFlags = ( "ar", "tar" );
$DefaultCompiler        = "ar";

( $Compiler, $Flags ) = &get_compiler( $DebugFlags, $ReleaseFlags, $DefaultCompiler, @CompilersPassedInFlags );

$TargetFile = $Target->get;

&GenerateBillofMat( $BillOfMaterialFile->get, $BillOfMaterialRpt, $TargetFile ) if ( defined $BillOfMaterialFile );

if ( defined $FootPrintFile )
{
 ( $tmpfhs, $FPSource ) = tempfile( 'omXXXXX', SUFFIX => '.c', UNLINK => 0 );
 close $tmpfhs;

 $FPObject = $FPSource;
 $FPObject =~ s/\.c/$\.o/;
 $FPObjKeep = "-bkeepfile:$FPObject";

 #push(@DeleteFileList,$FPSource); # don't push it in yet.
 push ( @DeleteFileList, $FPObject );

 $CompilerArguments = "-c -o $FPObject $FPSource";
 $CompilerFound     = "cc";
 $CompilerFound     = "gcc", $FPObjKeep = "-Xlinker -bkeepfile:$FPObject" if ( $BuildType eq "GNU Executable" );
 $CompilerFound     = "insure", $CompilerArguments = "-g " . $CompilerArguments, $FPObjKeep = "-bkeepfile:$FPObject" if ( $BuildType eq "Executable Insure" );

 #-- JAG - Case 3753 - some fixes for gcc 3.2 and linux
 if ( $^O =~ /linux/i )
 {
  $CompilerFound = "gcc" if ( $BuildType eq "Executable" );
 }

 #-- get the version of gcc
 if ( $CompilerFound eq "gcc" )
 {
  my $gccout = `gcc -dumpversion`;
  $FPObjKeep = "" if ( $gccout =~ /^3/ );
 }

 &GenerateFootPrint( $FootPrintFile->get, $TargetFile, $FPSource, $FPObject, $CompilerFound, $CompilerArguments );
} ## end if ( defined $FootPrintFile...

my $FETarget     = Openmake::File->new( $TargetFile );
my $FETargetFile = $FETarget->getFE;

my $Objs             = "";
my $SOLibs           = "";
my $ALibs            = "";
my $IncorrectFMTLibs = "";
my $CorrectFMTLibs   = "";
my $NoExtLibs        = "";

my @RawObjectList = $TargetDeps->getExt( qw(.O .SO .NOEXT .A) );

foreach my $RawObject ( @RawObjectList )
{
 next if ( $RawObject eq $FETargetFile );

 my $Object = $RawObject;
 if ( $Object =~ /\.a$/ )
 {
  $ALibs .= " $Object";
 }
 if ( $Object =~ /^lib.*\.a$/ )
 {
  $CorrectFMTLibs .= " $Object";
  next;
 }
 if ( $Object =~ /\.a$/ )
 {
  $IncorrectFMTLibs .= " $Object";
  next;
 }
 if ( $Object =~ /\.so$/ )
 {
  $SOLibs .= " $Object";
  next;
 }
 if ( $Object =~ /\.o$/ )
 {
  $Objs .= " $Object";
  next;
 }
 $NoExtLibs .= " $Object";
} ## end foreach my $RawObject ( @RawObjectList...

$Objs             =~ s/^ //;
$SOLibs           =~ s/^ //;
$ALibs            =~ s/^ //;
$IncorrectFMTLibs =~ s/^ //;
$CorrectFMTLibs   =~ s/^ //;
$NoExtLibs        =~ s/^ //;

unlink $TargetFile if ( -e $TargetFile );

$AllObjectsString = "$Objs $FPObject $SOLibs";

@AllObjectsList = split ( / +/, $AllObjectsString );

$nobjs = @AllObjectsList;

$StepDescription   = "Archiving $nobjs Objects\n";
$CompilerArguments = "$Flags $TargetFile $AllObjectsString";

if ( $nobjs > 450 )
{
 $BaseArchiverFlags = $Flags . " $TargetFile";
 $CompilerArguments = $BaseArchiverFlags;

 $counter = 0;
 foreach $ArchiveObject ( @AllObjectsList )
 {
  $CompilerArguments .= " $ArchiveObject";

  if ( $counter > 399 )
  {
   $StepDescription = "Archiving a set of $counter Objects (out of $nobjs)\n";
   &omlogger( "Begin", $StepDescription, "ERROR:", "$StepDescription succeeded.", $Compiler, $CompilerArguments, "", $RC, @CompilerOut );

   @CompilerOut = `$Compiler $CompilerArguments 2>&1`;
   $RC          = $?;

   &omlogger( "Intermediate", $StepDescription, "ERROR:", "ERROR: $StepDescription failed!", $Compiler, $CompilerArguments, "", $RC, @CompilerOut ), $RC = 1 if ( $RC != 0 );
   &omlogger( "Intermediate", $StepDescription, "ERROR:", "$StepDescription succeeded.", $Compiler, $CompilerArguments, "", $RC, @CompilerOut ) if ( $RC == 0 );

   if ( $RC != 0 )
   {
    push ( @DeleteFileList, $TargetFile );
    &ExitScript( "1", @DeleteFileList );
   }

   $CompilerArguments = $BaseArchiverFlags;
   $counter           = 0;
   next;
  } ## end if ( $counter > 399 )

  $counter++;
 } ## end foreach $ArchiveObject ( @AllObjectsList...

 # Do the last set, if we are left with one more ...
 if ( $CompilerArguments ne $BaseArchiverFlags )
 {
  $StepDescription = "Archiving final set of $counter Objects (out of $nobjs)\n";
  &omlogger( "Begin", $StepDescription, "ERROR:", "$StepDescription succeeded.", $Compiler, $CompilerArguments, "", $RC, @CompilerOut );

  @CompilerOut = `$Compiler $CompilerArguments 2>&1`;
  $RC          = $?;

  &omlogger( "Final", $StepDescription, "ERROR:", "ERROR: $StepDescription failed!", $Compiler, $CompilerArguments, "", $RC, @CompilerOut ), $RC = 1 if ( $RC != 0 );
  &omlogger( "Final", $StepDescription, "ERROR:", "$StepDescription succeeded.", $Compiler, $CompilerArguments, "", $RC, @CompilerOut ) if ( $RC == 0 );

  if ( $RC != 0 )
  {
   push ( @DeleteFileList, $TargetFile );
   &ExitScript( "1", @DeleteFileList );
  }
 }
} ## end if ( $nobjs > 450 )
else
{
 &omlogger( "Begin", $StepDescription, "ERROR:", "$StepDescription succeeded.", $Compiler, $CompilerArguments, "", $RC, @CompilerOut );

 @CompilerOut = `$Compiler $CompilerArguments 2>&1`;
 $RC          = $?;

 &omlogger( "Final", $StepDescription, "ERROR:", "ERROR: $StepDescription failed!", $Compiler, $CompilerArguments, "", $RC, @CompilerOut ), $RC = 1 if ( $RC != 0 );
 &omlogger( "Final", $StepDescription, "ERROR:", "$StepDescription succeeded.", $Compiler, $CompilerArguments, "", $RC, @CompilerOut ) if ( $RC == 0 );
}
