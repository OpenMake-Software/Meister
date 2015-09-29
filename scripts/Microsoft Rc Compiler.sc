use File::Find;
use File::Copy;
use Openmake::BuildOption;


use Fcntl ':flock';
$lockfile = $FinalTarget->getDPFE() . ".omlock";
open LOCKFILE, "> $lockfile";
flock LOCKFILE, LOCK_EX;

$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Microsoft\040Rc\040Compiler.sc,v 1.7 2010/09/01 21:27:48 steve Exp $';
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


#-- Include changes for MainWin on unix.
#   General description: Mainwin is a port of the Microsoft compilers to
#   Unix. Hence they use the same Openmake build types as the MS compilers
#   Attempt to push all Mainwin code to one 'if' block below
my $Is_Mainwin = 0;
$Is_Mainwin = 1 unless ( $^O =~ /win32/i);

#-- JAG - 08.01.05 - case 6072 - use the Unix MainWin rc (not rc.exe)
@CompilersPassedInFlags = ("rc");
$DefaultCompiler  = "rc";

#-- find the complier and default options
($Compiler,$Flags) = get_compiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);
if ($CFG eq "DEBUG")
{
 $build_options = Openmake::BuildOption->new($DebugFlags);
} 
else
{
 $build_options = Openmake::BuildOption->new($ReleaseFlags);
} 

#-- set the include directory
$ENV{'LIB'}     = '';

#-- JAG - 11.17.05 - case 6414 - get Include to include the ProjectVpath
$IncludeNL = $Include;
$IncludeNL =~ s|-I|\n-I|g;
$Include =~ s|-I "||g;
$Include =~ s|"|;|g;
$Include =~ s|"|;|g;
$Include =~ s| -I;||g;
$Include =~ s|^-I;||g;
$ENV{'INCLUDE'} = $Include;

#$ENV{'INCLUDE'} = $VPath->getString('',';') . "$ENV{'COMPILER'}\\mfc\\include;$ENV{'COMPILER'}\\include;$ENV{'COMPILER'}\\atlmfc\\include;$ENV{'COMPILER'}\\FrameworkSDK\\include;$ENV{'COMPILER'}\\PlatformSDK\\Include";
#$IncludeNL = $VPath->getString('-I"',"\"\n") . "-I \"$ENV{'COMPILER'}\\mfc\\include\"\n -I \"$ENV{'COMPILER'}\\include \"\n -I \"$ENV{'COMPILER'}\\atlmfc\\include\" \n -I \"$ENV{'COMPILER'}\\FrameworkSDK\\include\" \n -I \"$ENV{'COMPILER'}\\PlatformSDK\\Include\"\n";

#-- find the current working directory and the name of the target file
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
#
#-- JAG 06.15.04 - Case 4732. This doesn't work
@rclist = $TargetRelDeps->getExt(qw(.RC));
$TmpStr=$Target->getF;
$RCRelDeps = join(";",grep(/$TmpStr/,@rclist));
$FullSource = join("",grep(/$TmpStr/,$TargetDeps->getExt(qw(.RC))));
$Flags = $build_options->getOption4File($FullSource);

#-- JAG - 08.01.05 - case 6072 - specific changes for MainWin compiler
if ( $Is_Mainwin)
{
 print "\nRunning MainWin compiler\n";
 my $include = $ENV{'INCLUDE'};
 $include =~ s/;/:/g;
 $include =~ s/\\/\//g;
 $ENV{'INCLUDE'} = $include;
 print "\n\n-->INCLUDE is : $ENV{INCLUDE}\n\n";

 $FullTargetFile = "\"" . $TargetFile . "\"";
 $FullTargetFile =~ s/\\/\//g;

 #-- change / options to - options.
 $Flags =~ s|\s+/| -|g;
 $Flags =~ s|^/|-|;
 $CompilerArguments = "$Flags -fo$FullTargetFile \"$FullSource\"";
}
else
{
 #-- JAG - 09.27.04 - case 5056 - quote FullSource on command line in
 #                    case it has spaces.
 #-- JAG - 08.01.05 - $Defines no longer exists
 $CompilerArguments = "$Flags /fo$FullTargetFile \"$FullSource\"";
}

#$RelDir = join("\/",@pieces);
#Work around
#$Compiler = 'rc';

omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut);

# Issue 1795 do chdir just before compiler call
#-- JAG - 08.01.05 - case 6072 - $RelDir isn't used.
#chdir $RelDir unless ($RelDir eq "");

#-- get time of build .
$::build_time = localtime();
@CompilerOut = `$Compiler $CompilerArguments 2>&1`;
$RC = $?;

# Issue 1795 do chdir back just before logger call
#chdir $CurrDir unless ($RelDir eq "");

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
  }
  else
  {
   push @CompilerOut, "\nMainWin Target '$TargetFile' is of mixed case and\nnot found on FileSystem, although MainWin returned RC ==0\n";
   $RC = 1;
  }
 } #-- End: if $search_build
}

omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut) if ($RC == 0);

flock LOCKFILE, LOCK_UN;
close LOCKFILE;

#if ( ! $RC && $Is_Mainwin )
#{
# #-- copy mixed case
# use File::Copy;
# my $file = $FullTargetFile;
# $file =~ s/"//g; #"
# $file =~ s/\\/\//g;
# #-- get the directory
# my @p = split "/", $file;
# my $file = pop @p;
# my $path = join "/", @p;
#
# my $lc_file = lc $file;
#
# copy $path . "/". $lc_file, $path . "/" . $file;
#
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
