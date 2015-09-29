use File::Find;
use File::Copy;

use Fcntl ':flock';
$lockfile = $FinalTarget->getDPFE() . ".omlock";
open LOCKFILE, "> $lockfile";
flock LOCKFILE, LOCK_EX;


$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Microsoft\040Linker.sc,v 1.8 2011/04/27 20:42:04 steve Exp $';
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

$IntDirName = $IntDir -> get;
$TargetFile = $Target->get;

#-- Include changes for MainWin on unix
#   General description: Mainwin is a port of the Microsoft compilers to
#   Unix. Hence they use the same Openmake build types as the MS compilers
#   Attempt to push all Mainwin code to one 'if' block below
my $Is_Mainwin = 0;
$Is_Mainwin = 1 unless ( $^O =~ /win32/i);

#-- Handle whether or not to delete temporary files
$unlink = 1;

#-- Do not delete temporary response file if -ks flag is passed to om
$unlink = 0 if $KeepScript eq 'YES';

if ( $FinalTarget->getE =~ /ocx/i )
{
 #-- workaround for bug in link.exe - 5781, SAB
 #
 #   Link may create a .dll instead of an .ocx
 #   and place in current working directory instead of location
 #   specified by /out:

 if ( -f $FinalTarget->get ) {
  #-- check for dll with same name
  chmod 0777, $FinalTarget->getF . ".dll";
  unlink $FinalTarget->getF . ".dll";

 } elsif ( -f $FinalTarget->getFE ) {
  chmod 0777, $FinalTarget->getFE;
  unlink $FinalTarget->getFE;

 } elsif ( -f $FinalTarget->get ) {
  chmod 0777, $FinalTarget->get;
  unlink $FinalTarget->get;
 }
}


$ENV{'LIB'} = $VPath->getString('"','";');
if ( $Is_Mainwin)
{
 print "Running MainWin compiler\n";

 #-- JAG - 07.19.05 - case 5413/6092: According to MainWin docs, LIB is not
 #    used. From the Visual MainWin? 5.0.3R Help Docs
 #  The following environment variables are not supported by link.exe:
 #   TMP (temporary files directory)
 #   LIB (Use the environment variables LIBPATH for IBM AIX library path,
 #        SHLIB_PATH for HP-UX library path, and LD_LIBRARY_PATH for
 #        Sun Solaris and Linux library paths, instead.)
 #
 #-- For completeness, we will set all of them.
 #-- JAG - 07.22.05 - update from Lattice: need to not quote and use : separator
 $ENV{'LIBPATH'}         = $VPath->getString('',':');
 $ENV{'SHLIB_PATH'}      = $VPath->getString('',':');
 $ENV{'LD_LIBRARY_PATH'} = $VPath->getString('',':');

 $Defs = Openmake::File->new($TargetDeps->getExt(qw(.DEF)));
 $Def  = $Defs->getFE;
 $Def  = "\"" . $Def . "\"";

}
else
{
 $Def = $TargetDeps->getExtQuoted(qw(.DEF));
}

$ENV{'INCLUDE'} = "";

if ($FinalTarget->get eq $Target->get)
{

 GenerateBillofMat($BillOfMaterialFile->get,$BillOfMaterialRpt,$TargetFile) if ( defined $BillOfMaterialFile);

 if ( defined $FootPrintFile )
 {
  #-- create an empty C file for the footprinting
  ($tmpfhs,$FPSource) = tempfile('omfpXXXX', SUFFIX => '.c', UNLINK => $unlink );
  close $tmpfhs;

  $FPObject = $FPSource;
  $FPObject =~ s/\.c$/\.obj/;

  if ( $Is_Mainwin)
  {
   $FPObject =~ s/bj$//;
  }

  $CompilerArguments = "$FPSource /Fo$FPObject /Zl /c /nologo";
  $CompilerFound = "cl.exe";

  GenerateFootPrint($FootPrintFile->get,$TargetFile,$FPSource,$FPObject,$CompilerFound,$CompilerArguments);
 }
}

# Now process the linker 'compiler'
@CompilersPassedInFlags = ("link.exe", "lib.exe");
$DefaultCompiler  = "link.exe";

($Compiler,$Flags) = get_compiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);

$LibPath =~ s/-L/\/LIBPATH:/g;
$LibPathNL =~ s/-L/\/LIBPATH:/g;

my %BuildSettings;

$BuildSettings{LES} = "/DLES /DWIN32";
$BuildSettings{JNI} = "/DJNI";
$BuildSettings{APACHE_APR} = "/O2";

my @parts = split(/ /,$Flags);
my $part = "";
my $key = "";
my $setting = ""; 
$Flags = "";

foreach $part (@parts)
{
 if ($part =~ /BuildSettings:/)
 {
  ($key,$setting) = split(/:/,$part);
  $Flags .= $BuildSettings{$setting} . " ";
 }
 else
 {
  $Flags .= $part . " ";
 }
}

#-- Open temporary file to pass arguments to the linker
($tmph, $Rsp) = tempfile('omXXXXX', SUFFIX => '.rsp', UNLINK => $unlink );

if ($Target->getE =~ /lib/i || $Target->getE =~ /exp/i)
{
 $StepDescription = "Creating Win32 Export Library $TargetFile";

 if ($Target->get eq $FinalTarget->get)
 {
  $LibFlag=$Flags;
  $Flags  = " /out:\"" . $Target->getDPF . ".lib\"";

  my @dep_objs = $TargetDeps->getExtList(qw(.O .OBJ .LIB));
  my @clList = $TargetDeps->getExtList(qw(.CL)); 
  my $clfile = "";
  
  foreach $clfile (@clList)
  {
   open(FPCL,"<$clfile");
   my @lines = <FPCL>;
   close(FPCL);
   my $line = "";
   foreach $line (@lines)
   {
    $line =~ s/\n//g;
    push(@dep_objs,$line);
   }
  }
  
  if ( $Is_Mainwin)
  {
   #-- JAG - 07.22.05 - case 6092 - map .so and .a libraries to .lib libraries.
   #         do this by removing lib from front, and changing .so|.a -> .lib
   @dep_objs = ConvertSharedLib($TargetRelDeps->getExtList(qw(.O .OBJ .LIB .A .SO)) );

   #-- change Flags
   $Flags  = " /out:\"" . $Target->getDPF . ".lib\"";
  }

  print $tmph "$Flags\n";
  $CompilerArguments = "$LibFlag $Flags\n";

  foreach $Dep ( @dep_objs)
  {
   next if ($Dep eq $Target->get);
   print $tmph "\"" . $Dep . "\"\n";
   $CompilerArguments .= $Dep . "\n";
  }
  if (defined $FootPrintFile)
  {
   print $tmph "$FPObject\n";
   $CompilerArguments .= $FPObject . "\n";
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

   #-- use $DL for MainWin
   $DefName = $IntDir->get . "\\" . $FinalTarget->getF . ".def";

   my $def_file = $DefName;
   $def_file =~ s/\\/\//g if ( $Is_Mainwin);

   open (GENDEF,">$def_file");
   print GENDEF ("$DefLine\n");
   close(GENDEF);
  }

  $LibFlag = $Flags;
  $Flags = " /def:\"$DefName\" /out:\"" . $Target->getDPF . '.lib"';

  print $tmph "$Flags\n";
  print $tmph $LibPathNL;
  
  $CompilerArguments = "$LibFlag $Flags\n";

  my @dep_objs = $TargetDeps->getExtList(qw(.O .OBJ .LIB));
   my @clList = $TargetDeps->getExtList(qw(.CL)); 
  my $clfile = "";
  
  foreach $clfile (@clList)
  {
   open(FPCL,"<$clfile");
   my @lines = <FPCL>;
   close(FPCL);
   my $line = "";
   foreach $line (@lines)
   {
    $line =~ s/\n//g;
    push(@dep_objs,$line);
   }
  }
  
  if ( $Is_Mainwin)
  {
   @dep_objs = ConvertSharedLib($TargetRelDeps->getExtList(qw(.O .OBJ .LIB .A .SO)) );
  }

  foreach $Dep ( @dep_objs)
  {
   print $tmph "\"" . $Dep . "\"\n";
   $CompilerArguments .= $Dep . "\n";
  }
 }
}
else
{
 $StepDescription = "Performing C/C++ Link for $TargetFile";

 $DllDef = ' /dll' if ($FinalTarget->getE =~ /dll/i);
 $DllDef = ' /dll /implib:"'. $FinalTarget->getDPF .'.lib /def:' . $Def if ($FinalTarget->getE =~ /ocx/i);
 $DllDef = ' /def:' . $Def if ( $Def && $FinalTarget->getE =~ /exe/i);

 $Pdb = '"' . $IntDir->get . "\\" . $FinalTarget->getF . '.pdb"';
 $Map = '"' . $IntDir->get . "\\" . $FinalTarget->getF . '.map"';

 $Flags .= "$DllDef /pdb:$Pdb /map:$Map /out:" . $Target->getQuoted  if ($CFG eq 'DEBUG');
 $Flags .= "$DllDef /pdb:$Pdb /map:$Map /out:" . $Target->getQuoted  if ($CFG ne 'DEBUG');

 print $tmph "$Flags\n";
 print $tmph $LibPathNL;
 $CompilerArguments = "$Flags\n";

 my @dep_objs = $TargetDeps->getExtList(qw(.EXP .LIB .O .OBJ .RES .RBJ));
 my @clList = $TargetDeps->getExtList(qw(.CL)); 
 my $clfile = "";
 
 foreach $clfile (@clList)
  {
   open(FPCL,"<$clfile");
   my @lines = <FPCL>;
   close(FPCL);
   my $line = "";
   foreach $line (@lines)
   {
    $line =~ s/\n//g;
    push(@dep_objs,$line);
   }
  }

 if ( $Is_Mainwin)
 {
  @dep_objs = ConvertSharedLib($TargetRelDeps->getExtList(qw(.EXP .LIB .A .SO .O .OBJ .RES .RBJ)) );
 }

 foreach $Dep ( @dep_objs)
 {
  if( uc($Target->getE) eq '.DLL' )
  {
   $expLib = $Target->getAbsolute();
   $expLib =~ s/\.dll$/\.lib/i;
   next if $Dep eq $expLib; #-- don't add dll lib to dll
  }

  print $tmph "\"" . $Dep . "\"\n";
  $CompilerArguments .= $Dep. "\n";
 }

 if (defined $FootPrintFile)
 {
  print $tmph "\"" . $FPObject . "\"\n";
  $CompilerArguments .= "\"" . $FPObject . "\"\n";
 }

}

close $tmph;

#-- If we are doing '-ks' tell us the name of the response file

$CompilerArguments .= " ($Compiler $LibFlag /nologo \@$Rsp)" if $KeepScript eq 'YES';

omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$LibPathNL,$RC,@CompilerOut);

#-- get time of build .
$::build_time = localtime();
@CompilerOut = `$Compiler $LibFlag /nologo \@$Rsp 2>&1`;
$RC = $?;

if ( $FinalTarget->getE =~ /ocx/i and $RC == 0 ) {
 #-- workaround for bug in link.exe - 5781 SAB
 #
 #   Link may create a .dll instead of an .ocx
 #   and place in current working directory instead of location
 #   specified by /out:

 unless ( -f $FinalTarget->get ) {
  #-- check for dll with same name
 require File::Copy;

  if ( -f $FinalTarget->getF . ".dll" ) {
   move $FinalTarget->getF . ".dll", $FinalTarget->get;

  } elsif ( -f $FinalTarget->getFE ) {
   move $FinalTarget->getFE, $FinalTarget->get;
  }
 }
}

#$signcmd = "signtool sign /t http://timestamp.verisign.com/scripts/timstamp.dll /a \"" . $FinalTarget->get . "\" 2>&1";
#my @x = `$signcmd`;
#push(@CompilerOut, @x);

#-- see if the target was of mixed case. We may have to move the target.
#   Conditional requires that the MainWin compiler worked, the Target is
#   of mixed case and it doesn't 'exist' on the filesystem
$TargetFile =~ s|\\|/|g;
if ( !$RC && $Is_Mainwin && lc $TargetFile ne $TargetFile )
{
 #-- need to check existence separately to get timestamp.
 my $search_build = 0;
 if ( -e $TargetFile )
 {
  #-- check time stamp.
  my $target_time = (stat($TargetFile))[9];
  if ( $target_time <  $::build_time)
  {
   print "DEBUG: '$TargetFile' exists, but is out-of-date. Looking for lower-case build.\n" if ( defined $ENV{OMDEBUG});
   $search_build = 1;
  }
 }
 else
 {
  $search_build = 1;
 }

 if ( $search_build )
 {
  print "DEBUG: Searching for '$TargetFile'\n" if ( defined $ENV{OMDEBUG});
  $::build_dir = '.';
  #-- Check TargetFile for leading ".."
  $::match_path = $TargetFile;
  $::match_path =~ s|\\|/|g;
  if ( $::match_path =~ m|^\..| )
  {
   my @path = split /\//, $::match_path;
   my $p;
   while ( ( $p = shift @path) eq '..' )
   {
    $::build_dir .= '/..';
   }
   $::match_path = $p . '/' . (join '/', @path );
  }
  print "DEBUG: match path '$::match_path'\n" if ( defined $ENV{OMDEBUG});

  #-- do a file::find for the case-insenstive target.
  $::found_mw_file = '';
  find( \&mainwin_file, $::build_dir);

  #-- copy the file to the
  if ( $::found_mw_file )
  {
   print "DEBUG: found '$::found_mw_file'\n" if ( defined $ENV{OMDEBUG});
   copy( $::found_mw_file, $TargetFile );
   #-- also copy .rsb, .ilk, .pdb
   $::found_mw_file =~ s|\.(\w+)$||g;
   $TargetFile =~ s|\.(\w+)$||g;
   foreach my $ext ( '.rsb', '.ilk', '.pdb' )
   {
    if ( -e $::found_mw_file . $ext )
    {
     print "DEBUG: found '$::found_mw_file$ext', copying\n" if (defined $ENV{OMDEBUG});
     copy( $::found_mw_file . $ext, $TargetFile . $ext);
    }
   }
  }
  else
  {
   push @CompilerOut, "\nMainWin Target '$TargetFile' is of mixed case and\nnot found on FileSystem, although MainWin returned RC == 0\n";
   $RC = 1;
  }
 } #-- End: if $search_build
}

omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,$LibPathNL,$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$LibPathNL,$RC,@CompilerOut) if ($RC == 0);

flock LOCKFILE, LOCK_UN;
close LOCKFILE;
unlink($lockfile);

#------------------------------------------
# subroutine to filter .a .so files into .lib files
#  changes extension, removes lib from from of file name
#
#-- It now also removes leading path on the .lib files
sub ConvertSharedLib
{
 my @dep_objs = @_;

 foreach my $obj ( @dep_objs )
 {
  $obj =~ s/\//\\/g;
  #-- do simple thing. Split into path, file
  my @path = split /\\/, $obj;
  my $file = pop @path;

  #-- only remove lib prefix if extension is .a or .so
  if ( $file =~ /\.(so|a)$/ )
  {
   $file =~ s/^lib//;
   $file =~ s/\.(so|a)$/\.lib/;
   $obj = $file;
  }
  else
  {
   push @path, $file;
   $obj = join '\\', @path;
  }
 }

 return @dep_objs;
}

#------------------------------------------
# subroutine to find case-insensitive version of the built item
sub mainwin_file
{
 my $name = $File::Find::name;
 if ( $name eq lc ($::build_dir . '/' . $::match_path ))
 {
  print "DEBUG: Found lowercase '$name' eq Target '$::build_dir/$::match_path'\n"
   if ( defined $ENV{OMDEBUG} );
  #-- need to stat in the local dir
  my $mtime = (stat($_))[9];
  print "DEBUG: mtime '$mtime' compared to build time '$::build_time'\n"
   if ( defined $ENV{OMDEBUG});

  if ( (stat($_))[9] > $::build_time  )
  {
   print "DEBUG: found file '$name'\n" if ( defined $ENV{OMDEBUG});
   $::found_mw_file = $name;
  }
 }
}
