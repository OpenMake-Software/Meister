use Fcntl ':flock';
$lockfile = $FinalTarget->getDPFE() . ".omlock";
open LOCKFILE, "> $lockfile";
flock LOCKFILE, LOCK_EX;

$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Microsoft\040Midl\040Compiler.sc,v 1.7 2010/09/01 21:27:48 steve Exp $';

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
#
#  @PerlData = $AllDeps->load("AllDeps", @PerlData );
#  @PerlData = $RelDeps->load("RelDeps", @PerlData );
#  @PerlData = $NewerDeps->load("NewerDeps", @PerlData );

@PerlData = $TargetDeps->load("TargetDeps", @PerlData );

# @PerlData = $TargetRelDeps->load("TargetRelDeps", @PerlData );
#  @PerlData = $TargetSrcDeps->load("TargetSrcDeps", @PerlData );
 
####### Setting Compiler Choice and Search Behavior #######  
use Win32;

@CompilersPassedInFlags = ("midl");
$DefaultCompiler  = "midl";

#-- Find the compiler in the path and choose the set of
#   flags based on the $CFG environment variable

($Compiler,$Flags) = GetCompiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);

#-- Put the target name into a normal scalar
$TargetFile = $Target->get;

#-- get base filename for later
$DPFTargetFile = $Target->getDPF;

$MatchIdl = $Target->getF;

@IdlList = grep (/$MatchIdl\.idl$|$MatchIdl\.odl$/i,$TargetDeps->getExt(qw(.IDL .ODL)));
$Idl = shift(@IdlList);

$IdlSource = Openmake::File->new($Idl);

$IsIdl = 1 if $IdlSource->getE =~ /idl/i;
$IsOdl = 1 if $IdlSource->getE =~ /odl/i;

@VPathList = $VPath->getList;

foreach (@VPathList) {
 $ShortPath = Win32::GetShortPathName($_);
 next if ($ShortPath =~ /^\s*$/);
 
 $VPathStr .= "/I \"" . $ShortPath . "\"\n";
}

@ProjVPathList = $ProjectVPath->getList;

foreach (@ProjVPathList) {
 $ShortPath = Win32::GetShortPathName($_);
 next if ($ShortPath =~ /^\s*$/);
 
 $ProjVPath .= "/I \"" . $ShortPath . "\"\n";
}

$IncludeNL = $ProjVPath . $VPathStr;

#-- Handle whether or not to delete temporary files
$unlink = 1;

#-- Do not delete temporary response file if -ks flag is passed to om
$unlink = 0 if $KeepScript eq 'YES';

#-- Filter importlib statements
if ($Idl ne "")
{
 $IdlType =`type "$Idl"`;
 @IdlList = split(/\n/,$IdlType);

 ($tmpi, $Idl) = tempfile('omXXXXX', SUFFIX => $IdlSource->getE, UNLINK => $unlink );

 foreach $line (@IdlList)
 {
  if ($line =~ /^\s*importlib/ )
  {
   @tmplist = split(/[()\n]/,$line);

   pop(@tmplist);
   $file = pop(@tmplist);
   
   $file =~ s/\"//g; #"
   @tmplist = split(/\\/,$file);
   $file = pop(@tmplist);

   $hasdir = @tmplist;

   $file = "bin\\" . $file if ($hasdir > 0); 
   
   print $tmpi "importlib\($file\)\;\n" if $IsOdl;
   print $tmpi "importlib\(\"$file\"\)\;\n" if $IsIdl;
   
  }
  else
  {
   $line =~ s/\.\.\\//g;
   print $tmpi "$line\n";
  }
 } 

 close $tmpi;

}

#-- Choose compiler arguments based on target type
# Debug   Flags = /nologo /D_DEBUG /Oicf
# Release Flags = /nologo /DNDEBUG /Oicf

$CompilerArguments = $Flags;

if( $Target->getE =~ /tlb/i ) {
 $CompilerArguments .= " /tlb \"$DPFTargetFile" . ".tlb\"";

} else {
 #-- assume we are generating a header and c file
 $CompilerArguments .= " /h \"$DPFTargetFile" . ".h\" /iid \"$DPFTargetFile" . "_i.c\"";
 $CompilerArguments .= " /tlb \"$DPFTargetFile" . ".tlb\"";

}

#-- Open temporary file to pass arguments to the linker
($tmph, $Rsp) = tempfile('omXXXXX', SUFFIX => '.rsp', UNLINK => $unlink );

print $tmph "$CompilerArguments\n";
print $tmph "$IncludeNL";
print $tmph "$Idl";

# Need to close file so it is written to filesystem
close $tmph; # files will automatically be deleted when script exits

$CompilerArguments .= " $Idl";

$StepArguments = "\@$Rsp";

#-- prepare and do initial logging
$StepDescription = "Performing midl Compile for " . $Target->getQuoted;
omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut);

#-- Execute the actual compile command
@CompilerOut = `$Compiler $StepArguments 2>&1`;
$RC = $?;

#-- Do final logging

omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut), $RC = 1 if ($RC != 0);

omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut) if ($RC == 0);


flock LOCKFILE, LOCK_UN;
close LOCKFILE;