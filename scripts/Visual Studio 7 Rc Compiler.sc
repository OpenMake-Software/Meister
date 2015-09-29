use Fcntl ':flock';
$lockfile = $FinalTarget->getDPFE() . ".omlock";
open LOCKFILE, "> $lockfile";
flock LOCKFILE, LOCK_EX;


$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Visual\040Studio\0407\040Rc\040Compiler.sc,v 1.4 2010/09/01 21:27:48 steve Exp $';
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
#  @PerlData = $TargetDeps->load("TargetDeps", @PerlData );
@PerlData = $TargetRelDeps->load("TargetRelDeps", @PerlData );
#  @PerlData = $TargetSrcDeps->load("TargetSrcDeps", @PerlData );

@CompilersPassedInFlags = ("rc");
$DefaultCompiler  = "rc";

($Compiler,$Flags) = get_compiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);

$ENV{'LIB'}     = '';
$ENV{'INCLUDE'} = $ProjectVPath->getString('',';') . "$ENV{'COMPILER'}\\vc7\\include;$ENV{'COMPILER'}\\vc7\\atlmfc\\include;$ENV{'COMPILER'}\\vc7\\PlatformSDK\\include;";

$CurrDir = cwd();

$TargetFile = $Target->get;
$FullTargetFile = "\"" . $CurrDir . "\\" . $TargetFile . "\"";
$FullTargetFile =~ s/\//\\/g;

@ResSource = CopyLocal( $TargetDeps, $TargetRelDeps, ".");
# CopyLocal writes into dellist
push(@DeleteFileList,@dellist);

$StepDescription = "Performing Resource Compile for $TargetFile";

# Attention we need to execute in rel dir, where dependencies
# are copied to the script below determines this from the rc dep:
@rclist = $TargetRelDeps->getExt(qw(.RC));

$TmpStr=$Target->getF;

$RCRelDeps = join(";",grep(/$TmpStr/,@rclist));

@pieces = split(/[\\\/]/,$RCRelDeps);
$NewSource = pop(@pieces);

$Source = $NewSource unless ($Source =~ /^[\\\/]/ or $Source =~ /^[a-z]\:/i);

$CompilerArguments = "$Defines $Flags /fo$FullTargetFile $Source";
 
$RelDir = join("\/",@pieces);

#Work around
$Compiler = 'rc';

omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut);

# Issue 1795 do chdir just before compiler call
chdir $RelDir unless ($RelDir eq "");
@CompilerOut = `$Compiler $CompilerArguments 2>&1`;
$RC = $?;

# Issue 1795 do chdir back just before logger call
chdir $CurrDir unless ($RelDir eq "");

omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut) if ($RC == 0);

flock LOCKFILE, LOCK_UN;
close LOCKFILE;
