$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Visual\040Age\040C\040Project\040Compiler.sc,v 1.3 2005/06/06 16:18:05 jim Exp $';
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

@CompilersPassedInFlags = ("ahivacbld", "vacbld");
$DefaultCompiler  = "ahivacbld";

($Compiler,$Flags) = get_compiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);

$TargetFile = $Target->get;
$TmpSource = Openmake::File->new($TargetDeps->getExtQuoted(qw(.icc)));
$Source = $TmpSource->getF;

chmod 0777,$TargetFile;
unlink($TargetFile);

$CompilerArguments = "$Source $Flags $Defines ";

$StepDescription   = "Performing Visual Age Build for $TargetFile";
omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);

@CompilerOut = `ksh $Compiler $CompilerArguments 2>&1`;
$RC = $?;

$RC = 1 if (! -e $TargetFile);

omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut) if ($RC == 0);

