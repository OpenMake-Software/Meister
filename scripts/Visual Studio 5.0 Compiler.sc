use Fcntl ':flock';
$lockfile = $FinalTarget->getDPFE() . ".omlock";
open LOCKFILE, "> $lockfile";
flock LOCKFILE, LOCK_EX;

$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Visual\040Studio\0405.0\040Compiler.sc,v 1.6 2010/09/01 21:27:48 steve Exp $';
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
#-- Include changes for MainWin on unix
#   General description: Mainwin is a port of the Microsoft compilers to
#   Unix. Hence they use the same Openmake build types as the MS compilers
#   Attempt to push all Mainwin code to one 'if' block below
my $Is_Mainwin = 0;
$Is_Mainwin = 1 unless ( $^O =~ /win32/i);

@CompilersPassedInFlags = ("cl.exe");
$DefaultCompiler  = "cl.exe";

($Compiler,$Flags) = get_compiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);

$TargetFile = $Target->get;
$IntDirName = $IntDir -> get;

$Pch       = '"' . $IntDirName . "\\" . $FinalTarget->getF . '.pch"';
$Pdb       = '"' . $IntDirName . "\\" . $FinalTarget->getF . '.pdb"'; 
 
if ( $Is_Mainwin)
{
 @localC = CopyLocal($TargetDeps, $TargetRelDeps, $IntDir->get, qw(.c .cpp .cxx .C .CPP .CXX));
 $Source = $localC[0];
 $Source = "\"$Source\"";
}
else
{
 $Source = $TargetDeps->getExtQuoted(qw(.C .CPP .CXX));
}

if ($Target->getExt =~ /pch/i && $Source =~ /(stdafx)\.cpp/i)
{
 $TargetFile =  $Target->getPath . "\\$1.obj";
 $QuotedTargetFile = '"'. $TargetFile . '"';
}
else
{
 $TargetFile = $Target->get;
 $QuotedTargetFile = $Target->getQuoted;
}

$ENV{'LIB'}     = '';
$ENV{'INCLUDE'} = '';

$Flags .= ' /Fp' . $Pch . ' /Fo' . $QuotedTargetFile . ' /Fd' . $Pdb . ' /c ' . $Source if ($CFG eq 'DEBUG');
$Flags .= ' /Fp' . $Pch . ' /Fo' . $QuotedTargetFile .                 ' /c ' . $Source if ($CFG ne 'DEBUG');

$Flags =~ s/\/Yu/\/Yc/ if ($Target->getE =~ /pch/i || $Target->getFE =~ /stdafx\.obj/i);

($tmpfh, $rsp) = tempfile('omXXXXX', SUFFIX => '.rsp', UNLINK => 1 );

#-- Define include path.
if ( $Is_Mainwin)
{
 $ENV{'INCLUDE'} = $VPath->get;
 print $tmpfh "$Flags";

}
else
{
 $ENV{'INCLUDE'} = '';
 print $tmpfh "$Include $Flags";
}
close $tmpfh;

$StepDescription = "Performing C/C++ Compile for $TargetFile";
$CompilerArguments = "$Flags";

omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut);

print "($Compiler /nologo \@$rsp)" if defined $ENV{OMDEBUG};
#-- get time of build .
$::build_time = localtime();
@CompilerOut = `$Compiler /nologo \@$rsp 2>&1`;
$RC = $?;

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
   #-- also look for .o file
   copy( $::found_mw_file, $TargetFile );
   $::found_mw_file =~ s|\.obj$|\.o|;
   $TargetFile =~ s|\.obj$|\.o|;
   copy( $::found_mw_file, $TargetFile );
  }
  else
  {
   push @CompilerOut, "\nMainWin Target '$TargetFile' is of mixed case and\nnot found on FileSystem, although MainWin returned RC == 0\n";
   $RC = 1;
  }
 } #-- End: if $search_build
}

$StepError = "$StepDescription failed!";
omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut), push(@DeleteFileList,$rsp) if ($RC == 0);

flock LOCKFILE, LOCK_UN;
close LOCKFILE;

#if ( ! $RC && $Is_Mainwin && $Source =~ /(stdafx)\.cpp/i)
#{
# #-- copy mixed case
# use File::Copy;
# my $file = $Target->getPath . "/$1";
# my $lc_file = $Target->getPath . "stdafx";
#
# copy $lc_file . ".obj", $file . ".obj";
# copy $lc_file . ".o", $file . ".o";
#}

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

