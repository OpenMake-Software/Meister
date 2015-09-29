use Fcntl ':flock';
$lockfile = $FinalTarget->getDPFE() . ".omlock";
open LOCKFILE, "> $lockfile";
flock LOCKFILE, LOCK_EX;


$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Visual\040Studio\0407\040Linker.sc,v 1.5 2010/09/01 21:27:48 steve Exp $';
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

$IntDirName = $IntDir -> get;
$TargetFile = $Target->get;

$ENV{'LIB'} = $VPath->getString('"','";');
$ENV{'INCLUDE'} = "";

$Def = $TargetDeps->getExtQuoted(qw(.DEF));

if ($FinalTarget->get eq $Target->get)
{

 GenerateBillofMat($BillOfMaterialFile->get,$BillOfMaterialRpt,$TargetFile) if ( defined $BillOfMaterialFile);

 if ( defined $FootPrintFile )
 {
  ($tmpfhs,$FPSource) = tempfile('omXXXXX', SUFFIX => '.c', UNLINK => 0 );
  close $tmpfhs;
 
  $FPObject = $FPSource;
  $FPObject =~ s/\.c$/\.obj/;
 
  push(@DeleteFileList,$FPObject); # to keep source on failure
  
  $CompilerArguments = "$FPSource /Fo$FPObject /Zl /c /nologo";
  $CompilerFound = "cl";
 
  GenerateFootPrint($FootPrintFile->get,$TargetFile,$FPSource,$FPObject,$CompilerFound,$CompilerArguments);
 }
} 

# Now process the linker 'compiler'
@CompilersPassedInFlags = ("link");
$DefaultCompiler  = "link";

($Compiler,$Flags) = get_compiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);


($tmph, $Rsp) = tempfile('omXXXXX', SUFFIX => '.rsp', UNLINK => 1);

if ($Target->getE =~ /lib/i || $Target->getE =~ /exp/i)
{
 $StepDescription = "Creating Win32 Export Library $TargetFile";

 if ($Target->get eq $FinalTarget->get)
 {
  $LibFlag=$Flags;
  $Flags  = " /out:\"" . $Target->getDPF . ".lib\"";  
  
  ($tmph, $Rsp) = tempfile('omXXXXX', SUFFIX => '.rsp', UNLINK => 1);

  print $tmph "$Flags\n";
  $CompilerArguments = "$LibFlag $Flags\n";

  foreach $Dep ($TargetDeps->getExtList(qw(.OBJ .LIB))) 
  {
   next if ($Dep eq $Target->get); 
   print $tmph "\"$Dep\"\n";
   $CompilerArguments .= "$Dep\n";
  }
  if (defined $FootPrintFile)
  {
   print $tmph "$FPObject\n";
   $CompilerArguments .= "$FPObject\n";
  }
 }
 else
 {
  $DefName = $TargetDeps->getExt(qw(.DEF));
  $TargetName = $IntDir->get . "\\" . $Target->getF . ".lib"; 

  if ($DefName eq "")
  {
   if ($FinalTarget->get =~ /\.dll$/)
   {
    $DefLine = "LIBRARY \"" . $FinalTarget->getF . "\"";
   }
   else
   {
    $DefLine = "NAME \"" . $FinalTarget->getF . "\"";
   }
 
   $DefName = $IntDir->get . "\\" . $FinalTarget->getF . ".def";
 
   open (GENDEF,">$DefName");
   print GENDEF ("$DefLine\n");
   close(GENDEF);
  }
  $LibFlag = $Flags;
 
  $Flags = "/def:\"$DefName\" /out:\"" . $Target->getDPF . '.lib"';  
  print $tmph "$Flags\n";
  $CompilerArguments = "$LibFlag $Flags\n";

  foreach $Dep ($TargetDeps->getExtList(qw(.OBJ))) 
  {
   print $tmph "\"$Dep\"\n";
   $CompilerArguments .= "$Dep\n";
  }
 }
}
else
{
 $StepDescription = "Performing C/C++ Link for $TargetFile";

 $IDL = $IntDir->getAbsolute . "\\_" . $Target->getF . ".idl";
  
 $DllDef = ' /dll' if ($FinalTarget->getE =~ /dll/i);
 $DllDef = ' /dll /implib:"'. $FinalTarget->getDPF .'.lib /def:' . $Def if ($FinalTarget->getE =~ /ocx/i);
 
 $Pdb = '"' . $IntDir->get . "\\" . $FinalTarget->getF . '.pdb"';
 $Map = '"' . $IntDir->get . "\\" . $FinalTarget->getF . '.map"';
  
 $Flags .= " /IDLOUT:\"$IDL\" $DllDef /pdb:$Pdb /map:$Map /out:" . $Target->getQuoted  if ($CFG eq 'DEBUG');
 $Flags .= " /IDLOUT:\"$IDL\" $DllDef /pdb:$Pdb /map:$Map /out:" . $Target->getQuoted  if ($CFG ne 'DEBUG');
   
 print $tmph "$Flags\n";
 $CompilerArguments = "$Flags\n";

 foreach $Dep ($TargetDeps->getExtList(qw(.EXP .LIB .OBJ .RES))) 
 { 
  print $tmph "\"$Dep\"\n";
  $CompilerArguments .= "$Dep\n";
 }

 foreach $Dep ($TargetDeps->getExtList(qw(.DLL))) 
 { 
  print $tmph "/ASSEMBLYMODULE:\"$Dep\"\n";
  $CompilerArguments .= "/ASSEMBLYMODULE:\"$Dep\"\n";
 }

 if (defined $FootPrintFile)
 {
  print $tmph "\"$FPObject\"\n";
  $CompilerArguments .= "$FPObject\n";
 }
 
 $ENV{'INCLUDE'} = $ProjectVPath->getString('',';') . "$ENV{'COMPILER'}\\vc7\\include;$ENV{'COMPILER'}\\vc7\\atlmfc\\include;$ENV{'COMPILER'}\\vc7\\PlatformSDK\\include;";
}

close $tmph;

omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$LibPathNL,$RC,@CompilerOut);

@CompilerOut = `$Compiler $LibFlag /nologo \@$Rsp 2>&1`;
$RC = $?;

if ($Target->getExt =~ /dll/i && -e $IDL)
{
 $TlbIn = $Target->getDPF . "_com.dll";

 copy $TargetFile, $TlbIn;
 
 print "regsrv /s /c \"$TlbIn\"\n";
 @regout = `regsvr32 /s /c "$TlbIn" 2>&1`;
 
 $Compiler = "tlbimp \"$TlbIn\" /out:\"$TargetFile\"";
 print "$Compiler\n";

 @tlbout = `$Compiler 2>&1`;
 
 push(@CompilerOut,@regout);
 push(@CompilerOut,@tlbout);
}

omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,$LibPathNL,$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$LibPathNL,$RC,@CompilerOut), push(@DeleteFileList,$Rsp) if ($RC == 0);


flock LOCKFILE, LOCK_UN;
close LOCKFILE;
unlink($lockfile);
