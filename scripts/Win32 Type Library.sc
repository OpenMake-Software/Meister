use Win32;
$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Win32\040Type\040Library.sc,v 1.3 2005/06/06 16:18:05 jim Exp $';
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

#########################################
#-- Load Openmake Variables from Data File
#   Uncomment Openmake objects that need to be loaded

#  @PerlData = $AllDeps->load("AllDeps", @PerlData );
#  @PerlData = $RelDeps->load("RelDeps", @PerlData );
#  @PerlData = $NewerDeps->load("NewerDeps", @PerlData );
@PerlData = $TargetDeps->load("TargetDeps", @PerlData );
#  @PerlData = $TargetRelDeps->load("TargetRelDeps", @PerlData );
#  @PerlData = $TargetSrcDeps->load("TargetSrcDeps", @PerlData );
}

####### Setting Compiler Choice and Search Behavior #######  

@CompilersPassedInFlags = ("midl");
$DefaultCompiler  = "midl";

($ScriptCompiler,$Flags) = get_compiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);

$TargetFile = $Target->get;
$DPFTargetFile = $Target->getDPF;

$LogFile  = $DPFTargetFile . ".log";
$Rsp      = $DPFTargetFile . ".rsp";
$MatchIdl = $Target->getF . ".idl";

@IdlList = grep (/$MatchIdl$/,$TargetDeps->getExt(qw(.IDL .ODL)));
$Idl = shift(@IdlList);

@VPathList = $VPath->getList;
foreach (@VPathList)
{
 $ShortPath = Win32::GetShortPathName($_);
 next if ($ShortPath =~ /^\s*$/);
 
 $VPath .= "/I \"" . $ShortPath . "\"\n";
}

@ProjVPathList = $ProjVPath->getList;
foreach (@ProjVPathList)
{
 $ShortPath = Win32::GetShortPathName($_);
 next if ($ShortPath =~ /^\s*$/);
 
 $ProjVPath .= "/I \"" . $ShortPath . "\"\n";
}

$IncludeNL = $ProjVPath . $VPath;

if ($Idl ne "")
{
 $IdlType =`type $Idl`;
 @IdlList = split(/\n/,$IdlType);

 open (OMIDL,">om.idl");

 foreach $line (@IdlList)
 {
  if ($line =~ /importlib/ )
  {
   @tmplist = split(/[()\n]/,$line);

   pop(@tmplist);
   $file = pop(@tmplist);
   $file =~ s/\"//g; #"
   @tmplist = split(/\\/,$file);
   $file = pop(@tmplist);

   $hasdir = @tmplist;

   $file = "bin\\" . $file if ($hasdir > 0); 
   
   print OMIDL "importlib\(\"$file\"\)\;\n";
  }
  else
  {
   print OMIDL "$line\n";
  }
 } 
 close(OMIDL);
 $Idl = "om.idl";
}

# Debug   Flags = /nologo /D_DEBUG /Oicf
# Release Flags = /nologo /DNDEBUG /Oicf

$CompilerArguments = $Flags . "/tlb \"$Target->get\" /h $DPFTargetFile" . ".h /idd \"$DPFTargetFile\"" . "_i.c";

open (RSP,">$Rsp");
print RSP "$CompilerArguments\n";
print RSP "$IncludeNL";
print RSP "$Idl";
close(RSP);

$CompilerArguments .= " $Idl";

$Compiler = get_compiler("","","regtlb","");
$StepArguments = "-u $Target->get";

$StepDescription = "Unregistering $Target->get";
omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut);

@CompilerOut = `$Compiler $StepArguments 2>&1`;
$RC = $?;

omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$StepArguments,"",$RC,@CompilerOut), push(@DeleteFileList,$Target->get), ExitScript("1",@DeleteFileList) if ($RC != 0);
omlogger("Intermediate",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$StepArguments,"",$RC,@CompilerOut); 

$StepArguments = "\@$Rsp";

$StepDescription = "Performing midl Compile for $Target->get";
omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut);

@CompilerOut = `$ScriptCompiler /nologo $StepArguments 2>&1`;
$RC = $?;

omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$ScriptCompiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut), push(@DeleteFileList,$Target->get), ExitScript("1",@DeleteFileList) if ($RC != 0);
omlogger("Intermediate",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$ScriptCompiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut); 

$CompilerArguments = "-s $Target->get";

$StepDescription = "Registering $Target->get";
omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);

@CompilerOut = `$ScriptCompiler $CompilerArguments 2>&1`;
$RC = $?;

omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$ScriptCompiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut), push(@DeleteFileList,$Target->get), ExitScript("1",@DeleteFileList) if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$ScriptCompiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut); 
 
