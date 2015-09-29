use Fcntl ':flock';
use File::Copy;
use File::Path;
use File::stat;
use File::Spec;
use File::Temp qw/tempfile/;
use Cwd qw{ abs_path realpath}; #Need this to resolve ../'s in paths. ADG 070606 - 6965
use Openmake::File;
use Openmake::FileList;

use Win32::File qw{ GetAttributes SetAttributes}; #-- case SLS-66

$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/MSBuild\040Compile.sc,v 1.3 2008/02/21 18:17:54 jim Exp $';
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
@PerlData = $AllDeps->load("AllDeps", @PerlData );
@PerlData = $RelDeps->load("RelDeps", @PerlData );
@PerlData = $TargetDeps->load("TargetDeps", @PerlData );
@PerlData = $TargetRelDeps->load("TargetRelDeps", @PerlData );

####### Setting Compiler Choice and Search Behavior #######

#-- evaluate debug ENV
our $Debug = 1 if ( $ENV{OM_DNET_DEBUG});

#-- evaluate the sleep time and max sleep for locking during web builds
our $Sleep_Time = 30;
our $Max_Sleep = 100;
our @Ignore_Exts = qw( .dll .exe .cs .vb) ;

#-- code to lock for solution builds
our $om_temp_prefix;
my @msbuild_projects;
my @msbuild_clean_projects;

our $Cwd;

@CompilersPassedInFlags = ("msbuild");
$DefaultCompiler  = "msbuild";

($Compiler,$Flags) = get_compiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);
if ( $Compiler =~ m{\s+})
{
 $Compiler = '"' . $Compiler . '"'; #add quotes if $CFG has spaces in it
}

$IntDirName = $IntDir->get;

$CFG = "Release" if not defined $CFG;
if ($CFG =~ /\s+/g)
{
 $CFG = '"' . $CFG . '"'; #add quotes if $CFG has spaces in it
}

my $cwd = cwd();
$Cwd = $cwd; #-- global version
$cwd =~ s{\\}{/}g;
$cwd =~ s{/}{\\}g;

my @p_projs = $TargetRelDeps->get();
my @projs = grep { m{\.\w+proj$} } @p_projs; #-- all possible projects of .XXproj
my @proj_exts = @projs;
map { m{(\.\w+proj)}; $_ = $1; } @proj_exts;
#-- this is the MSBuild project that replaces the solution file

## - START ADG fix to pass generated proj to compiler

my $main_project = $projs[0];
#my $main_project = pop @projs;

#foreach $p (@projs)
#{
# if ($p !~ m{(_om_\d{8})\.\w{2}proj$} )
# {
# $main_project = $p;
#  last;
# }
#}

## - END ADG

my $CompilerArguments;
my $sln_file = '';
#-- determine if this is a website compile

#CopyAndBackup();

if ( $BuildType =~ m{website}i )
{
 $sln_file = ($TargetDeps->getExt(qw(.SLN)))[0];
 my ( $tfh, $tmp_sln) =  tempfile('om_XXXXXX', DIR => '.', SUFFIX => '.sln' );
 close $tfh;
 open $tfh, '>:utf8', $tmp_sln;
 push @DeleteFileList, $tmp_sln unless ( $KeepScript =~ m{yes}i );

 #-- read the current solution file, write out only the parts that match this target
 #-- JAG 12.14.07 - case IUD-90 - because the website can refer to other items, make
 #   the temp soln contain everything. This ends up being pretty pointless as
 #   we are just doing a copy of the .sln file.
 #
 #my $guid = '';
 my $sfh;

 #-- see if the sln file is UTF-8
 open $sfh, '<:utf8', $sln_file or die "Cannot open '$sln_file'\n";

 while ( <$sfh> )
 {
   print $tfh $_;
 }
 close $sfh;
 close $tfh;

 #-- call msbuild against the temp soln
 $CompilerArguments = qq{ "$tmp_sln" /t:Build /p:Configuration=$CFG $Flags /nologo 2>&1};

}
else
{

 #-- JAG - need to copy non-proj information if listed
 push @Ignore_Exts, @proj_exts;
 my @add_localres = CopyExcludeLocal($AllDeps,$RelDeps,$cwd, @Ignore_Exts );
 push @localres, @add_localres;

 my $platform_flag = '';
 if ( $PLATFORM )
 {
  $platform_flag = "/p:Platform=$PLATFORM";
 }
 $CompilerArguments = qq{"$main_project" /p:BuildProjectReferences=false /t:Build /p:Configuration=$CFG $platform_flag /nologo 2>&1};

 my $StepDescription = "Compiling MSBuild File '$main_project'";
 my $RC = 0;
 omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
}

if ($main_project =~ /\.wixproj/)
{
 @wxslist = $TargetDeps->getList(qw(.wxs));

 foreach $wxs (@wxslist)
 {
  my $renfile = $wxs . ".omtemp";
  copy($wxs,$renfile);
  push(@RenamedItems,$renfile);

  open(FPWXS,"<$wxs");
  @lines = <FPWXS>;
  close(FPWXS);

  open(FPWXS,">$wxs");
  foreach $line (@lines)
  {
   if ($line =~ /define SourceDir/i)
   {
    $line = "<?define SourceDir=\"" . $cwd . "\"?>\n";
   }
   print FPWXS $line;
  }
  close(FPWXS);
 }
}

if ($main_project =~ /\.csproj|.vcxproj/)
{
 @assemblylist = grep(/AssemblyInfo/i,$TargetDeps->getList(qw(.cs .cpp)));

 foreach $asm (@assemblylist)
 {
  my $renfile = $asm . ".omtemp";
  copy($asm,$renfile);
  push(@RenamedItems,$renfile);

  open(FPASM,"<$asm");
  @lines = <FPASM>;
  close(FPASM);

  open(FPASM,">$asm");
  foreach $line (@lines)
  {
   $line = "[assembly: AssemblyFileVersion(\"" . $ENV{VERSIONSTR} . "\")]\n" if ($line =~ /^\[assembly: AssemblyFileVersion/i && length($ENV{VERSIONSTR}) > 0);
   
   #disabled -jmcmorris since do not know what this is 2011-07-25
   #$line = "[assembly:AssemblyVersionAttribute(\"" . $ENV{VERSIONSTR} . "\")]\n" if ($line =~ /^\[assembly:AssemblyVersionAttribute/i && length($ENV{VERSIONSTR}) > 0);
   print FPASM $line;
  }
  close(FPASM);
 }
}

if ($main_project =~ /\.vcxproj/)
{
 @assemblylist = grep(/\.rc/,$TargetDeps->getList());

 foreach $asm (@assemblylist)
 {
  my $renfile = $asm . ".omtemp";
  copy($asm,$renfile);
  push(@RenamedItems,$renfile);

  open(FPASM,"<$asm");
  @lines = <FPASM>;
  close(FPASM);

  open(FPASM,">$asm");
  $verstr = $ENV{VERSIONSTR};
  $verstrdot = $ENV{VERSIONSTR};
  $verstr =~ s/\./,/g;
  
  foreach $line (@lines)
  {
   $line = "FILEVERSION $verstr \n" if ($line =~ /FILEVERSION/ && length($ENV{VERSIONSTR}) > 0);
   $line = "PRODUCTVERSION $verstr \n" if ($line =~ /PRODUCTVERSION/ && length($ENV{VERSIONSTR}) > 0);

   $line = "VALUE \"FileVersion\", \"$verstrdot\" \n" if ($line =~ /FileVersion/ && length($ENV{VERSIONSTR}) > 0);
   $line = "VALUE \"ProductVersion\", \"$verstrdot\" \n" if ($line =~ /ProductVersion/ && length($ENV{VERSIONSTR}) > 0);
 
   print FPASM $line;
  }
  close(FPASM);
 }
}


#-- JAG - 12.27.05 - grab STDOUT too
$Command = "$Compiler $CompilerArguments";
my @CompilerOut = `$Command 2>&1 `;
$CompilerRC = $?;

if ( $CompilerRC != 0 )
{
 $RC = 1;
 $StepError = "$Compiler did not execute properly. ";
 push(@CompilerOut,$StepError);
 omlogger("Final",$StepDescription,"ERROR:","$StepError.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
}
else
{
 $RC = 0;
 $StepError = "$Compiler Completed successfully \n";
 push(@CompilerOut,$StepError);

 push(@DeleteFileList,@dellist);
 omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
}

if ( $BuildType =~ m{website}i  && $RC == 0 )
{
 #-- create the target
 open $tfh, '>', $TargetFile;
 print $tfh localtime(), "\n";
 close $tfh;

 #-- update the timestamp to be earlier than the solution file. This ensures that the website is rebuilt every time.
 my $mtime;
 my $s = stat( $sln_file);
 $mtime = $s->mtime - 61; #-- more than a minute
 $files = utime $mtime, $mtime, $TargetFile;
}
else
{
 #CleanUp( $sln, $om_temp_prefix, \@msbuild_projects);
}

ExitScript($RC,@DeleteFileList);

##############################
sub CleanUp
##############################
{
 #-- This subroutine cleans up after the script has run. It
 #   performs the following:
 #
 #   1. strips .omtemp off all of the original VBPROJ and CSPROJ files
 #   2. touches every compiled object so they all have the same time stamp
 #
 #   Inputs:
 #   Solution File (optional) -- look in solution file to determine which Projects were built on this call
 #   to Devenv
 #   om temp postfix (optional ) -- clean up temporary scripts made by MSBuildProject
 #
 #   Returns:
 #    N/A
 #
 my $sln = shift;
 my $om_temp = shift;
 my $msbuild_projs_ref = shift;

 #-- loop through all of the renamed VBPROJ and CSPROJ files and strip off .omtemp
 #-- pull original project file attributes from hash and set to touched project file
 if ( @RenamedItems != ())
 {
  foreach $file (@RenamedItems)
  {
   $rename = $file;
   $rename =~ s{\.omtemp$}{};
   rename ( $file, $rename);
  }
 }

 #-- need to do attribs outside of lookup.
 print "DEBUG: Cleanup: using all Relative Dependencies to determine timestamp updating\n" if ( $Debug );
 my @Attribs = $RelDeps->getExtList(qw(.DLL .EXE .VBPROJ .CSPROJ .VDPROJ .VCPROJ));
 foreach my $project ( @Attribs )
 {
  if ($Project_Attributes{$project})
  {
   my $attribArgs = $Project_Attributes{$project} . '"' . $project . '"';
   $attribout = `attrib $attribArgs`;
   if ($attribout)
   {
   print "DEBUG: The following errors occured when reapplying attributes to $project: $attribout\n" if ($Debug);
   }
   else
   {
   print "DEBUG: Attributes successfully reapplied to $project\n" if ($Debug);
   }
  }
 }
}

#------------------------------------------------------------------
sub CopyAndBackup
{
 my $om_temp;
 my @return_projs;
 my %found; #-- check to see that the project file hasn't been listed twice due to relative ".." path issues

 my @full_msbuild_projs = ();
 foreach my $proj ( @projs )
 {
  $proj =~ s{\\}{/}g;
  #-- match to temporary for this project
  if ( $proj =~ m{(_om_\d{8})\.\w{2}proj$} )
  {
   $om_temp = $1;

   my $tmpdir = Openmake::File->new($proj);
   
   #-- test for duplicate projects
   my $real_name = realpath($tmpdir->getDP()); #-- need real_path to resolve .. in names
   $real_name .= "//" . $tmpdir->getFE();
   
   next if ( $found{$real_name});
   $found{$real_name} = 1;
   push @full_msbuild_projs, $proj;

  #-- get the canonical name. Remove any '..' crap
  my $ofile = Openmake::File->new($proj);
  my $real_proj = $ofile->getF();
  $real_proj =~ s{$om_temp$}{};
  $real_proj = join ( '/', $ofile->getP(), $real_proj . $ofile->getE() );
  $real_proj =~ s{\\}{/}g;

  #-- at this point, we need to set the timestamp to the same as the source code
  my @s = stat $real_proj;
  my $mtime;
  if ( ref $s[0] ne 'SCALAR' )
  {
   $mtime = $s[0]->[9];
  }
  else
  {
   $mtime = $s[9];
  }
  
  #-- see if it exists in the current directory, back it up
  if ( -e $real_proj )
  {
   #-- need to copy to a backup location
   #-- this doesn't do what we thought. It's not a REGEX, a straight '.' is fine
   $back_proj = $real_proj . ".omtemp";
   chmod 0755, $real_proj;
   rename( $real_proj, $back_proj);
   push @RenamedItems, $back_proj;
  }

  #-- move the temp file to the 'real' name
  #chmod 0755, $proj;
  copy( $proj, $real_proj);

  utime $mtime, $mtime, $real_proj;

  #-- Add to our list of returns
  push @return_projs, $real_proj;
  }
 }
 return ( $om_temp, @return_projs);
}


