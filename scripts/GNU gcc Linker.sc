$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/GNU\040gcc\040Linker.sc,v 1.3 2005/06/06 16:18:05 jim Exp $';
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

@CompilersPassedInFlags = ("gcc", "g++", "ld", "c++");
$DefaultCompiler  = "gcc";

( $Compiler, $Flags ) =
  &get_compiler( $DebugFlags, $ReleaseFlags, $DefaultCompiler,
 @CompilersPassedInFlags );

$TargetFile = $Target->get;

$OracleHome = $ENV{"ORACLE_HOME"};
$OracleHome = "/u/oracle/product/8.0.6" if ( $OracleHome eq "" );

@found = grep ( /^\d+\./, split ( /\//, $OracleHome ) );

$OracleVersion = pop ( @found );

&GenerateBillofMat( $BillOfMaterialFile->get, $BillOfMaterialRpt, $TargetFile ) if ( defined $BillOfMaterialFile );

if ( defined $FootPrintFile )
{
 ( $tmpfhs, $FPSource ) = tempfile( 'omXXXXX', SUFFIX => '.c', UNLINK => 0 );
 close $tmpfhs;

 $FPObject = $FPSource;
 $FPObject =~ s/\.c/$\.o/;
 $FPObjKeep = "-bkeepfile:$FPObject" if ($^O !~ /linux/i);

 #push(@DeleteFileList,$FPSource); # don't push source file in yet.
 push ( @DeleteFileList, $FPObject );
 
 $CompilerArguments = "-c -o $FPObject $FPSource";
 $CompilerFound     = "cc";
 $CompilerFound     = "gcc", $FPObjKeep = "-Xlinker -bkeepfile:$FPObject" if ( $BuildType eq "GNU Executable" );
 $CompilerFound = "insure", $CompilerArguments = "-g " . $CompilerArguments, $FPObjKeep = "-bkeepfile:$FPObject"  if ( $BuildType eq "Executable Insure" );

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
} #-- end if ( defined $FootPrintFile...

$FETarget = Openmake::File->new($TargetFile);
$FETargetFile = $FETarget->getFE;

$Objs = "";
$SOLibs = "";
$ALibs = "";
$IncorrectFMTLibs = "";
$CorrectFMTLibs = "";
@CorrectFMTLibList = ();
$NoExtLibs = "";

@RawObjectList = $TargetDeps->getExt(qw(.O .OBJ .SO .NOEXT .A .SL));


$AORFile = $TargetDeps->getExt(qw(.AOR));

foreach $RawObject (@RawObjectList)
{
 next if ($RawObject =~ /$FETargetFile$/);

 #-- shorten object path to relative if in fact it is relative
 $RawObjectObject = Openmake::File->new($RawObject);
 $Object = $RawObjectObject->getRelative();
 
 #-- JAG - 10.13.04 - case 4499 - UNIX spaces. Quote objects
 
 if ($Object =~ /\/lib([^\/\.]+)\.a$/)
 {
  $Object =~ /\/lib([^\/\.]+)\.a$/;
  $Object = $1;
  $Object = " -l$Object";

  push(@CorrectFMTLibList, $Object);
  next;  
 }

 if ($Object =~ /\/lib([^\/\.]+)\.sl$/)
 {
  $Object =~ /\/lib([^\/\.]+)\.sl$/;
  $Object = $1;
  $Object = " -l$Object";
  
  push(@CorrectFMTLibList, $Object);
  next;  
 }

 if( $Object =~ /\/lib([^\/\.]+)\.so$/) {

  $Object =~ /\/lib([^\/\.]+)\.so$/;
  $Object = $1;
  $CorrectFMTSOLibs .= " -l$Object";

  next;
 }

 if ($Object =~ /\.a$/)
 {
  $IncorrectFMTLibs .= " \"$Object\"";
  next;
 } 

 if( $Object =~ /\.so$/) {

  $SOLibs .= " \"$Object\"";
  next;
 }
 if ( $Object =~ /\.o$/ )
 {
  $Objs .= " \"$Object\"";
  next;
 }
 if ( $Object =~ /\.obj$/ )
 {
  $Objs .= " \"$Object\"";
  next;
 }
 $NoExtLibs .= " \"$Object\"";
}

$Objs             =~ s/^ //;
$SOLibs           =~ s/^ //;
$ALibs            =~ s/^ //;
$IncorrectFMTLibs =~ s/^ //;
$NoExtLibs        =~ s/^ //;

@CorrectFMTLibList = &OrderLibs( $AORFile, @CorrectFMTLibList ) if ( $AORFile ne "" && -e $AORFile );
$CorrectFMTLibs = join ( " ", @CorrectFMTLibList );

$CompilerArguments = "$Flags $Defines $PerlLibs -o $TargetFile $Objs $FPObject $NoExtLibs $SOLibs $CorrectFMTSOLibs $CorrectFMTLibs $IncorrectFMTLibs "; 

$ENV{LD_LIBRARY_PATH} = $VPath->getString( '', ':' ) . $ENV{LD_LIBRARY_PATH};

#############
# Link It
#############

unlink $TargetFile if ( -e $TargetFile );

$CompilerArguments = $FPObjKeep . " " . $CompilerArguments;

#-- If there is nothing that needs searching through the -L's
#   then don't put them there (need to shorten command line)
if ( @CorrectFMTLibList == () and $CorrectFMTSOLibs eq '' ) {
 $LibPathNL = '';
 $LibPath = '';
}

$StepDescription = "Performing Link for $TargetFile";
&omlogger(  "Begin", $StepDescription, "ERROR:", "$StepDescription succeeded.", $Compiler,  $CompilerArguments, $LibPathNL, $RC, @CompilerOut );

@CompilerOut = `$Compiler $LibPath $CompilerArguments 2>&1`;

$RC = $?;

omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,$LibPathNL,$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$LibPathNL,$RC,@CompilerOut) if ($RC == 0);
 
