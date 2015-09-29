use Fcntl ':flock';
$lockfile = $FinalTarget->getDPFE() . ".omlock";
open LOCKFILE, "> $lockfile";
flock LOCKFILE, LOCK_EX;


$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Visual\040Studio\0407\040Compiler.sc,v 1.4 2010/09/01 21:27:48 steve Exp $';
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

@CompilersPassedInFlags = ("cl");
$DefaultCompiler  = "cl";

($Compiler,$Flags) = get_compiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);

$TargetFile = $Target->get;
$IntDirName = $IntDir -> get;

$Pch       = '"' . $IntDirName . "\\" . $FinalTarget->getF . '.pch"';
$Pdb       = '"' . $IntDirName . "\\" . $FinalTarget->getF . '.pdb"'; 
 
$Source = $TargetDeps->getExtQuoted(qw(.C .CPP .CXX));

if ($Target->getExt =~ /pch/i && $Source =~ /stdafx\.cpp/i)
{
 $Stdafx = $TargetRelDeps->getExt(qw(.CPP .CXX));
 $Stdafx =~ s/stdafx\.cpp/stdafx\.obj/i;
 
 $TFile = Openmake::File->new("$IntDirName\\$Stdafx");
 $TFile->mkdir;
 $TargetFile = $TFile->getQuoted;
}
else
{
 $TargetFile = $Target->getQuoted;
}

$ENV{'LIB'}     = '';
$ENV{'INCLUDE'} = '';

$AssemblySearch = $Include;
$AssemblySearch =~ s|[-/]I|/AI|ig;

$AssemblySearchNL = $IncludeNL;
$AssemblySearchNL =~ s|[-/]I|/AI|ig;
$IncludeNL .= $AssemblySearchNL;

$Flags .= ' /Fp' . $Pch . ' /Fo' . $TargetFile . ' /Fd' . $Pdb . ' /c ' . $Source if ($CFG eq 'DEBUG');
$Flags .= ' /Fp' . $Pch . ' /Fo' . $TargetFile .                 ' /c ' . $Source if ($CFG ne 'DEBUG');

$Flags =~ s/\/Yu/\/Yc/ if ($Target->getE =~ /pch/i || $Target->getFE =~ /stdafx\.obj/i);

#print "$Compiler $Defines $Flags\n$IncludeNL\n\n" if ($Silent ne 'Yes');

($tmpfh, $rsp) = tempfile('omXXXXX', SUFFIX => '.rsp', UNLINK => 1 );

print $tmpfh "$Defines $Include $AssemblySearch $Flags";
close $tmpfh;

$StepDescription = "Performing C/C++ Compile for $TargetFile";
$CompilerArguments = "$Defines $Flags";

omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut);
@CompilerOut = `$Compiler /nologo \@$rsp 2>&1`;
$RC = $?;
$StepError = "$StepDescription failed!";
omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut), push(@DeleteFileList,$rsp) if ($RC == 0);

flock LOCKFILE, LOCK_UN;
close LOCKFILE;

