# Openmake Perl
use Openmake::Snapshot;
$ScriptName = $Script->get;
$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Microsoft\040Visual\040Jpp.sc,v 1.3 2005/06/06 16:18:05 jim Exp $';
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
 

$ScriptHeader = "Beginning Microsoft Visual J++ Build...";
$ScriptFooter = "Finished Microsoft Visual J++ Build";$ScriptDefault = "Initial";
$ScriptDefaultHTML = "Initial";
$ScriptDefaultTEXT = "Initial";
$HeaderBreak = "True";
$StepDescription = "Microsoft Visual J++ Build for $E{$FinalTarget->get}";

#omlogger( 'header');

$Verbose = 1 if $Quiet =~ /no/i;$omdebug = 1 if $ENV{OMDEBUG} ne '';

####### Setting Compiler Choice and Search Behavior #######  

# If the flags are going to determine the compiler,
# set the list @AvailableCompilers to all available
# compilers that can be used with this script, otherwise
# leave the list blank, i.e. set it to ().

# If you are assuming that the compiler will be determined from
# the flags passed set the variable $CompilerFound to blank. 
# If you want to use a default compiler, irrespective of flags
# set $CompilerFound to the compiler name


#-- Get a text file containg a list of classes
$ProjectFile = $TargetDeps->getExt(qw(.vpj));

#-- Get the full path of the compiler
@AvailableCompilers = qw( devenv );
$CompilerFound  = "devenv";

($Compiler,$Flags) = GetCompiler($DebugFlags,$ReleaseFlags,$CompilerFound,@AvailableCompilers);

#-- Get Classpath from dependency
$ClassPath = GetClasspath( $TargetDeps );
$ClassPath =~ s/^$ePathDL//;

#-- Format classpath for output
$ClassPathNL = $ClassPath;
$ClassPathNL =~ s/$PathDL/\n /g;
$IncludeNL = "\n\nClasspath:\n $ClassPathNL\n";

#-- Read in Project file and translate from UNICODE
# to normal text
$tmpvpj = 'omtmp.vjp';

`type $ProjectFile > $tmpvjp`;

open( VJP, "<$tmpvjp");
@lines = <VJP>;
close VJP;
push @DeleteFilesList, $tmpvjp

open( OMVJP, ">om.vjp");

foreach (@line) {
 if( /^VJCPATH\=/ ) {
  $_ = "VJCPATH=$ClassPath\n";
 }

 print OMVJP "$_";
}

close OMVJP;

#-- Define convenience vars
$TargetFile = $Target->get;
$Target->mkdir;
$TargetDir = $Target->getPath;

$CompilerArguments = "$Defines $Flags om.vjp";

print "$Compiler $CompilerArguments 2>&1\n";
@CompilerOut = `$Compiler $CompilerArguments 2>&1`;
$RC = $?;

$StepError = "ERROR: $StepDescription failed!";
omlogger("Abort",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut) if ($RC == 0);

chmod 0775, $TargetFile;
push(@DeleteFileList,$Target->get) if ($RC != 0);

########################################################
# Take After Snapshot
$afterSnapshot = Openmake::Snapshot->new( $IntDir->get );

# Write output for case compile failed.
$RC = 1 if ($RC != 0);

if ( $RC == 1 ) {
 #-- compile failed completely, ant didn't run at all
 $StepError = 'Microsoft Visual Jpp.sc: 00: Build failed.';
 omlogger("Final",$StepDescription,"FAILED",$StepError,$Compiler,$CompilerArgs,"",$RC,@CompilerOut);

} else {
 $RC = 0;

 # Compare snapshots
 @Buildees = LeftSnapshotOnly( $afterSnapshot, $beforeSnapshot );

 if ( $Verbose ) {
  push( @Footer, "\n\nBuilt the following files:\n\n" );
  foreach ( @Buildees ) {
   push( @Footer, " $_\n"
  }
 }

 $Footer = join('', @Footer);

 #-- create packages file to be used by Jar task and others
 unless ( open(RSP, ">$E{$Target->get}") ) {
  $StepError = "Microsoft Visual Jpp.sc: 02: Couldn't open $E{$Target->get}.\n" ;
  omlogger('Final',$StepDescription,"FAILED","ERROR: $StepError","","","",1,"");
 }

 foreach (@Buildees) {
   print RSP "$_\n";
 }
 close RSP;
 omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArgs,"",$RC,@CompilerOut);
}

ExitScript $RC, @dellist;