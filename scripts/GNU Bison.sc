$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/GNU\040Bison.sc,v 1.1 2006/03/28 23:55:33 sean Exp $';
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

####### Setting Compiler Choice and Search Behavior #######  

use File::Copy;

@CompilersPassedInFlags = ("bison");
$DefaultCompiler  = "bison";

($Compiler,$Flags) = GetCompiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);

$TargetFile = $Target->get;

$Source = $TargetDeps->getExt(qw(.Y));

$CompilerArguments = $Flags . " $Defines " . " -o " . $Target->get . " $Source";

$StepDescription = "Performing Yacc operation for " . $Target->get;
omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);

@CompilerOut = `$Compiler $CompilerArguments 2>&1`;

$RC = $?;

omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut) if ($RC == 0);
