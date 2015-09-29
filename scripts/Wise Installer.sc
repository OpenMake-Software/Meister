$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Wise\040Installer.sc,v 1.3 2005/06/06 16:18:06 jim Exp $';
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

####### Setting Compiler Choice and Search Behavior #######  

@CompilersPassedInFlags = ("wfwi");
$DefaultCompiler  = "wfwi";

($Compiler,$Flags) = get_compiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);

$TargetFile = $Target->get;
$WsiSource = $TargetDeps->getExtQuoted(qw(.WSI));
$WsiFile = Openmake::File->new($WsiSource);
$MsiFile = $TargetDeps->getExtQuoted(qw(.MSI));

$MsiFile = $WsiFile->getF . ".msi" if ($MsiFile eq "");

CopyLocal($TargetDeps,$TargetRelDeps,".",qw( .wsi ));

$CompilerArguments = '"' . $WsiFile->get . '" /c  /o "' . $MsiFile . '"';

$StepDescription   = "Performing Wise Install Package Creation for $TargetFile";
omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);

@CompilerOut = `$Compiler $Include $CompilerArguments 2>&1`;
$RC = $?;

omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut) if ($RC == 0);



