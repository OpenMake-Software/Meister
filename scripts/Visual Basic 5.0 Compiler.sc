#########################################
#-- Use module settings
use Win32::Registry;
use File::Copy;
use File::Temp qw(tempfile);
use Openmake::BuildOption;
use Win32::File;

#########################################
#-- Set script version number
#
$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Visual\040Basic\0405.0\040Compiler.sc,v 1.16 2009/04/20 17:49:19 steve Exp $';
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

 $ScriptVersion = ": $module, v$version";
}

#########################################
#-- Load Openmake Variables from Data File
#   Uncomment Openmake objects that need to be loaded

@PerlData = $AllDeps->load("AllDeps", @PerlData );
@PerlData = $RelDeps->load("RelDeps", @PerlData );
#  @PerlData = $NewerDeps->load("NewerDeps", @PerlData );
@PerlData = $TargetDeps->load("TargetDeps", @PerlData );
@PerlData = $TargetRelDeps->load("TargetRelDeps", @PerlData );
#  @PerlData = $TargetSrcDeps->load("TargetSrcDeps", @PerlData );

#########################################
#-- Define global variables
my $IntDirName = $IntDir->get;
my @DependencyList  = $TargetDeps->getList;
my @DllList = $TargetDeps->getExt(qw(.DLL));

my $vbp = $TargetDeps->getExt(qw(.VBP));
my $Log = $Target->getF . ".Log";
my $TargetPath = $Target->getDP;
my $RelTarget = $Target->get;
my $TargetQuotedDP = '"'. $Target->getDP . '"';
my $TargetQuotedFE = '"'. $Target->getFE . '"';

#-- We allow certain settings in the .vbp to be controlled from the TGT
#   file.
#   The following hash lists these options as keys, and refers to an array
#   of types.
my %allowed_vbp_options =
                  (
                    'BoundsCheck'      => [ 0, 1 ],
                    'CodeViewDebugInfo' => [ 0, 1 ],
                    'CompatibleMode'   => [ '"0"' , '"1"', '"2"' ],
                    'CondComp'         => '^".+"$',
                    'Description'      => '^".+"$',
                    'CompilationType'  => [ 0, 1 ],
                    'FavorPentiumPro(tm)' => [ 0, 1 ],
                    'FDIVCheck'        => [ 0, 1 ],
                    'FlPointCheck'     => [ 0, 1 ],
                    'MajorVer'         => '^\d+$',
                    'MinorVer'         => '^\d+$',
                    'NoAliasing'       => [ 0, 1 ],
                    'OptimizationType' => [ 0, 1, 2 ],
                    'OverflowCheck'    => [ 0, 1 ],
                    'RemoveUnusedControlInfo' => [ 0, 1 ],
                    'RevisionVer'      => '^\d+$',
                    'ThreadingModel'   => [ 0, 1, 2 ],
                    'ThreadPerObject'  => [ 0, 1 ],
                    'Title'            => '^".+"$',
                    'UnroundedFP'      => [ 0, 1 ],
                    'UpgradeActiveXControls' => [ 0, 1 ],
                    'VersionComments'  => '^".+"$',
                    'VersionCompanyName' => '^".+"$',
                    'VersionFileDescription' => '^".+"$',
                    'VersionLegalCopyRight'  => '^".+"$',
                    'VersionLegalTrademarks' => '^".+"$',
                    'VersionProductName' => '^".+"$'
                  );
#-- GLW - 08.31.07 - ErrOnMissingCompatComponent was added as a new build type
#option to force the build to fail if the Compatiblity Component referenced by
#a vbp is missing AND binary compatibility mode is in enabled. If this value is
#not set, the previous behavior will be used where a warning is displayed and
#compatibility is disabled.

#-- hash to hold vbp options that are specified in the TGT options
my %used_vbp_options = ();
#-- hash to control if we have seen the option while parsing the vbp
my %found_vbp_options = ();

####### Get original .vbp file attributes ######## LRL 10/12/2006
#-- Replace with module
#my $attrib;
#
#my @tmp = split(/ /, `attrib \"$vbp\"`);
#pop @tmp;
#foreach my $t (@tmp)
#{
# $attrib = $attrib . $t if $t =~ /\w+/;
#}
#my @origattribs = split(//,$attrib);

my $vbp_attrib;
Win32::File::GetAttributes($vbp, $vbp_attrib);

####### Setting Compiler Choice and Search Behavior #######

my @CompilersPassedInFlags = ("vb5", "vb6");
my $DefaultCompiler  = "vb6";

my ($vbexe,$Flags) = get_compiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);

$Compiler = $vbexe;
@templist = @DllList;
$RelTarget =~ s/\\/\\\\/g;

#-- flag for Compatible binary somewhere in the Search Path
my $CompatModuleNotFound = 0;

#-- Env variable override of non-compatibility
#   $nocompat is a flag to see if NON-COMPATIBILITY was requested from
#   the command line. This is requested by setting the $ENV NOCOMPAT=yes || =1
#

#########################################
#-- JAG 01.25.05 - case 5425. Get information from build type options.
#-- determine the configuration
#
my $buildopt;
$builopt = ($CFG eq "DEBUG" ) ? Openmake::BuildOption->new($DebugFlags)
                   : Openmake::BuildOption->new($ReleaseFlags) ;

#-- parse the options, looking for defined options. Validate against allowed options
my @wanted_options = $buildopt->getBuildTaskOptions( $BuildTask );

#-- see if any are overridden by the env var
my @vb6_env_options = Openmake::SmartSplit($ENV{VB6_OPTIONS});
my %vb6_env_options;
foreach ( @vb6_env_options)
{
 my ( $key, @vals) = split /=/;
 my $val = join "=", @vals;
 $vb6_env_options{$key} = $val;
}

#-- allow for special envs for Paul @ CA
#   Additional elements can be added by cloning the following lines
#
#-- JAG - 02.15.05 - set ENV in build jobs can add trailing whitespace. Chomp it off.

if ( $ENV{VB6_CONDCOMP})
{
 $vb6_env_options{'CondComp'} = $ENV{VB6_CONDCOMP} ;
 $vb6_env_options{'CondComp'} =~ s/\s+$//;
}

if ( $ENV{VB6_MAJORVER})
{
 $vb6_env_options{'MajorVer'} = $ENV{VB6_MAJORVER} ;
 $vb6_env_options{'MajorVer'} =~ s/\s+$//;
}

if ( $ENV{VB6_MINORVER})
{
 $vb6_env_options{'MinorVer'} = $ENV{VB6_MINORVER} ;
 $vb6_env_options{'MinorVer'} =~ s/\s+$//;
}

if ( $ENV{VB6_REVISIONVER})
{
 $vb6_env_options{'RevisionVer'} = $ENV{VB6_REVISIONVER} ;
 $vb6_env_options{'RevisionVer'} =~ s/\s+$//;
}

foreach my $opt ( @wanted_options )
{
 #-- split on = sign
 my ( $key, @vals) = split /=/, $opt;
 my $val = join "=", @vals;
 #-- GLW - 08.31.07 - set a flag if ErrOnMissingCompatComponent is set
 if ($key == "ErrOnMissingCompatComponent")
 {
  if ($val=="1")
  {
     $ErrOnMissingCompatComponent = 1;
     push(@CompilerOut, "\Info: Option 'ErrOnMissingCompatComponent' is in effect.\n");
  }
  else
  {
     $ErrOnMissingCompatComponent = 0;
  }
 }
 #-- see if we override with ENV
 $val = $vb6_env_options{$key} if ( defined $vb6_env_options{$key} );

 #-- see if key is in allowed_vbp_options.
 if ( defined $allowed_vbp_options{$key} )
 {
  my $ref_or_string =  $allowed_vbp_options{$key};
  if ( ref $ref_or_string eq "ARRAY" )
  {
   #-- it's an array of allowed values
   my @poss_options = @{$ref_or_string};
   foreach my $poss_opt ( @poss_options)
   {
    if ( $val == $poss_opt)
    {
     #-- set used hash
     $used_vbp_options{$key} = $val;
     $found_vbp_options{$key} = 0;
     last;
    }
   }

   #-- see if we missed an option
   if ( ! $used_vbp_options{$key} )
   {
    push(@CompilerOut, "\tWarning: TGT option '$key=$val' did not match one of expected values '@poss_options' \n");
   }
  }
  else
  {
   #-- its a pattern match.
   if ( $val =~ m!$ref_or_string! )
   {
    $used_vbp_options{$key} = $val;
    $found_vbp_options{$key} = 0;
   }
   else
   {
    push(@CompilerOut, "\tWarning: TGT option '$key=$val' did not match to expected format '$ref_or_string' \n");
   }
  }
 }
}

#-- JAG - 02.11.05 - if the user set the ENVs without setting a correspoding
#   option in the TGT, deal with that here
foreach my $key ( keys %vb6_env_options )
{
 #-- can check that used_vbp_options is not defined. If it were, it would have
 #   already been set with the value from the ENV
 if ( defined $allowed_vbp_options{$key} && ! $used_vbp_options{$key} )
 {
  $used_vbp_options{$key} = $vb6_env_options{$key};
  $found_vbp_options{$key} = 0;
 }
}

#-- allow for script backwards-compatibilty thru NOCOMPAT env.
$used_vbp_options{'CompatibleMode'} = '"0"' if ( $ENV{NOCOMPAT} =~ /y(es)?/i || $ENV{NOCOMPAT} == 1 );
$used_vbp_options{'CompatibleMode'} == '"0"' ? $nocompat = 1 : $nocompat = 0;

#-- Handling binary or project compatibility is trickier than just specifying
#   an option. We have to be sure that the previous exe or dll (Target) is
#   available somewhere in the Search Path

if ($TargetAsDep->get eq "")
{
 $CompatModuleNotFound = 1;
}
else
{
 if (! -e $RelTarget && ! $nocompat )
 {
  #-- copy the Existing Relative target local just in case
  #   we need it for Binary Compatibility
  # Changed as part of update to case 7011 ADG 05.17.06
  my $targetDependency = $TargetAsDep->get;
  if ($targetDependency =~ /^\\/i) {
   if ($targetDependency !~ /^\\\\/i) {
    $targetDependency = "\\$targetDependency";
   }
  }
  copy($targetDependency,$RelTarget);
 }

 $ProjectDir=Openmake::File->new($TargetRelDeps->getExt(qw(.VBP)))->getDP;

 if (-e $RelTarget)
 {
  if($ProjectDir ne "" && $ProjectDir ne "." )
  {
   $TmpFile = Openmake::File->new($ProjectDir . "\\" . $RelTarget);
   $TmpFile->mkdir;
   copy ($RelTarget, $ProjectDir . "\\" . $RelTarget);
   push(@DeleteFileList,$ProjectDir . "\\" . $RelTarget);
  }
 }
}

#########################################
#-- Generate Bill of Materials if Requested
#
GenerateBillofMat($BillOfMaterialFile->get,$BillOfMaterialRpt,$Target->get) if ( defined $BillOfMaterialFile);


#########################################
#-- Generate Footprint
#
my ( $FPSource, $RelFPSource);
if ( defined $FootPrintFile )
{
 #-- create an empty bas file for the footprinting
 my $relpath = Openmake::File->new($TargetRelDeps->getExt(qw(.VBP)))->getDP;

 $relpath = undef if ( $relpath eq "" or $relpath eq '.' );
 $relpath =~ s{\\}{/}g;
 my $outdir = $relpath || '.';
 unless ( -d $outdir )
 {
  mkfulldir( $outdir . '\\' );
 }

 my $unlink;
 ( $KeepScript eq 'YES' ) ? $unlink = 1 : $unlink = 0;

 ($tmpfhs, $FPSource) = tempfile('omfpXXXX', DIR => $outdir, SUFFIX => '.bas', UNLINK => $unlink );
 close $tmpfhs;
 $RelFPSource = Openmake::File->new($FPSource)->getFE();

 GenerateFootPrint( {
                    'FootPrint' => $FootPrintFile->get(),
                    'TargetFile' => $TargetFile,
                    'FPSource' => $FPSource,
                    'FPType'   => 'VB5|6',
                    'Compiler' => 'no compile'
                    }
                   );

}

#########################################
#-- Begin parsing VBP
@templist = @DependencyList;
$cnt = @templist;
foreach $fullpath (@templist)
{
 $fn = OMSplitPath($fullpath,'fe');
 $fn = lc($fn);
 $fullpath = lc($fullpath);

 $DepList{$fn} = $fullpath;
}

unless ( open(SRC,"$vbp") )
{
 $StepError = "Can't open source file: $vbp\n";
 push @CompilerOut, $StepError;
 $RC = 1;
 omlogger("Begin",$StepDescription,"ERROR:","ERROR: $StepError","",$CompilerArguments,"",$RC,@CompilerOut);

 push(@DeleteFileList,$Target->get);
 push(@DeleteFileList,$Log);
 push(@DeleteFileList,@dellist);
 omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepError",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
 ExitScript($RC,@DeleteFileList);
}
@SourceLines = <SRC>;
close(SRC);

@NewSource = @SourceLines;    #Save a copy of @SourceLines for after it has been processed so we can write out the
         #new Revision Number if it is changed by AutoIncrementVer=

                              #Now that we have covered all cases where the user might define new version info,
                              #we set our control variable for writing changes to source VBP

$updateVerInfo = "true" if ($used_vbp_options{'MajorVer'} || $used_vbp_options{'MinorVer'} || $used_vbp_options{'RevisionVer'});
my $auto_inc = 0;


#########################################
#-- Creating random name for temporary VB project file
#   Returning temporaryfileHandle and name of temp VB project file
my ($tmpfh, $outvbp) = tempfile('omXXXXX', SUFFIX => '.vbp', UNLINK => 0 );
close($tmpfh);

unless ( open(VBP,">$outvbp") )
{
 $StepError = "Can't write to file: $outvbp\n";
 push @CompilerOut, $StepError;
 $RC = 1;
 omlogger("Begin",$StepDescription,"ERROR:","ERROR: $StepError","",$CompilerArguments,"",$RC,@CompilerOut);
 push(@DeleteFileList,$Target->get);
 push(@DeleteFileList,$Log);
 push(@DeleteFileList,@dellist);
 omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepError",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
 ExitScript($RC,@DeleteFileList);
}

#-- keep these the same for backwards compatibility. Could go
#   into $found_vbp_options
$FoundPath32         = "N";
$FoundExeName32      = "N";
$FoundCompatibleName = "N";

my $module = 0;
foreach (@SourceLines)  # loads up next line in '$_' each time
{
 chomp($_);  # remove trailing carriage return

 SCAN:
 {
  #-- see if we have seen a .bas file
  if ( m{Module\s*=} && ! $module )
  {
   $module++;
   if ( $RelFPSource )
   {
    print VBP "Module=OpenmakeBuildAudit; $RelFPSource\n";
   }
  }

  if(/=/)
  {
   @words   = split(/=/, $_);
   $keyword = shift(@words);
   $value   = join('=',@words);

   if (/(AutoIncrementVer)/)
   {
    $auto_inc = shift(@words);
   }

   #-- JAG - 12.22.03 - incorporate changes made by Paul Sager, Accenture, 20-Dec-2003
   #                    for Binary Compatibility
   #
   # At this point we're reading through the input VBP. When we find the line with the
   # specified Compatibility, we'll update variable $CompatMode with the value found in the
   # VBP.
   #
   #-- change this to be in keeping with other general options.
   #    1. use value specified in the option. Possilbly override option with NOCOMPAT env.
   #    2. if not specified, use what is in the vbp
   #    3. If either is project/binary compatible, but we can't find the reference
   #       file, set to no-compatible.

   if( /^\s*CompatibleMode\s*/ )
   {
    my $vbp_compat_mode = shift(@words);

    #-- what happens next depend on how the compatibility was decided (in the
    #   .vbp or overridden by the environment variable)
    #
    #-- $vbp_compat_mode comes from the .vbp
    #
    #-- see if we have an option at all
    my $CompatString = " Binary"; #-- default

    if ( defined $used_vbp_options{'CompatibleMode'} )
    {
     $CompatString = " Project" if ( $used_vbp_options{'CompatibleMode'} eq '"1"' );

     #-- see if we have target in SP
     if ($CompatModuleNotFound )
     {
#-- GLW - 08.31.07 - If ErrOnMissingCompatComponent is set, abort the build, otherwise, warn and disable compatibilty mode.
         if ($ErrOnMissingCompatComponent=="1")
         {
            $StepError = "Error: Could not find " . $RelTarget . " in Search Path. Aborting...\n";
            push @CompilerOut, $StepError;
            $RC = 1;
            omlogger("Begin",$StepDescription,"ERROR:","ERROR: $StepError","",$CompilerArguments,"",$RC,@CompilerOut);
            omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepError",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
            close(VBP);
            push ( @DeleteFileList, $outvbp) unless $KeepScript =~ /YES/i;
            ExitScript($RC,@DeleteFileList);
         }
         else
         {
      push(@CompilerOut, "Error: Could not find " . $RelTarget . " in Search Path. Turning $CompatString Compatibility OFF.");
      $used_vbp_options{'CompatibleMode'} = '"0"';
     }
    }
    }
    elsif ( $vbp_compat_mode ne '"0"' )
    {
     #-- see what vbp proj wants
     $CompatString = " Project" if ( $vbp_compat_mode eq '"1"' );
     if ($CompatModuleNotFound )
     {
#-- GLW - 08.31.07 - If ErrOnMissingCompatComponent is set, abort the build, otherwise, warn and disable compatibilty mode.
         if ($ErrOnMissingCompatComponent=="1")
         {
            $StepError = "Error: Could not find " . $RelTarget . " in Search Path. Aborting...\n";
            push @CompilerOut, $StepError;
            $RC = 1;
            omlogger("Begin",$StepDescription,"ERROR:","ERROR: $StepError","",$CompilerArguments,"",$RC,@CompilerOut);
            omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepError",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
            close(VBP);
            push ( @DeleteFileList, $outvbp) unless $KeepScript =~ /YES/i;
            ExitScript($RC,@DeleteFileList);
         }
         else
         {
      push(@CompilerOut, "Error: Could not find " . $RelTarget . " in Search Path. Turning $CompatString Compatibility OFF.");
      $vbp_compat_mode = '"0"';
     }
     }

     #-- set option based on vbp
     $used_vbp_options{'CompatibleMode'} = $vbp_compat_mode;
    }

    #-- set info on what we are using
    if ( $used_vbp_options{'CompatibleMode'} eq '"1"' ||
         $used_vbp_options{'CompatibleMode'} eq '"2"'
       )
    {
     push(@CompilerOut, "\nInfo: Using " . $TargetAsDep->get . " for $CompatString Compatibility. $CompatString Compatibility is ON.");
    }
   }

   $_ = $keyword;
   $Type=$value if (/^\s*Type\*/);

   #-- these writes are custom, and go before general option handling
   WriteLine($keyword,$TargetQuotedDP), $FoundPath32="Y",         last SCAN if( /^\s*Path32\s*/          );
   WriteLine($keyword,$TargetQuotedFE), $FoundExeName32="Y",      last SCAN if( /^\s*ExeName32\s*/       );
   WriteLine($keyword,$Target->getQuoted  ), $FoundCompatibleName="Y", last SCAN if( /^\s*CompatibleEXE32\s*/ );
   WriteLine($keyword,Process_Object($value)),     last SCAN if( /^\s*Object\s*/   );
   WriteLine($keyword,Process_Reference($value)),  last SCAN if( /^\s*Reference\s*/);

   #WriteLine($keyword,$vbp_compat_mode),        last SCAN if( /^\s*CompatibleMode\s*/   );

   #-- check generalized options.
   foreach my $key ( keys %used_vbp_options )
   {
    if ( m!^\s*\Q$key\E! && $used_vbp_options{$key} )
    {
     WriteLine($keyword, $used_vbp_options{$key} );
     $found_vbp_options{$key} = 1;
     last SCAN;
    }
   }

   #-- write out the line
   WriteLine($keyword, $value);
  }
  else
  {
   $NewLine = $_;
   if ($NewLine =~ /\[MS Transaction Server\]/)
   {
    #-- if we reach the "MS Transaction Server" line, we have to
    #   write out information that is requested, but not already handled above
    #
    $FoundNewStuff = "on";

    WriteLine("Path32",$TargetQuotedDP)        if ($FoundPath32 eq "N");
    WriteLine("ExeName32",$TargetQuotedFE)     if ($FoundExeName32 eq "N");
    WriteLine("CompatibleEXE32",$Target->getQuoted) if ($FoundCompatibleName eq "N");
    #WriteLine("CondComp", $CondComp) if ( $FoundCondComp eq "N" && $CondComp);

    #-- check generalized options.
    foreach my $key ( keys %used_vbp_options )
    {
     if ( ! $found_vbp_options{$key} )
     {
      WriteLine( $key, $used_vbp_options{$key} );
      $found_vbp_options{$key} = 1;
     }
    }
   }

   print VBP "$NewLine\n";
  }
 }  ## End SCAN:
}  ## next line

if ($FoundNewStuff ne "on")
{
 WriteLine("Path32",$TargetQuotedDP)        if ($FoundPath32 eq "N");
 WriteLine("ExeName32",$TargetQuotedFE)     if ($FoundExeName32 eq "N");
 WriteLine("CompatibleEXE32",$Target->getQuoted) if ($FoundCompatibleName eq "N");
 foreach my $key ( keys %used_vbp_options )
 {
  if ( ! $found_vbp_options{$key} )
  {
   WriteLine( $key, $used_vbp_options{$key} );
   $found_vbp_options{$key} = 1;
  }
 }
}

close(VBP);

#-- Case 2893: Here we needed to copy local all deps that OM scanner doesn't pick up excluding
#   those with extensions that VB doesn't look for locally.

$cwd = cwd();
CopyExcludeLocal($AllDeps, $RelDeps,$cwd, qw(.dll .exe .ocx .olb .vbp));

unlink $Log if ( -e $Log);

# JAG - 11.18.02
# Add fix for case where executable uses 'App.Title' method internally
# access application name. In previous case, this would default to 'omXXXXX.exe'
# instead of the true application name

my $vbpbak;
my $vbploc = $TargetRelDeps->getExt(qw(.VBP));
if ( -e $vbploc )
{
 #-- need to copy to a backup location
 $vbpbak = $vbploc . "_$outvbp";
 chmod 0755, $vbploc;
 copy( $vbploc, $vbpbak );
}
else
{
 push ( @DeleteFileList, $vbploc) unless $KeepScript =~ /YES/i;
}

#-- rename the temp PROJ file ($outvbp) to the original ($vbploc)
copy ( $outvbp, $vbploc);
push ( @DeleteFileList, $outvbp) unless $KeepScript =~ /YES/i;

$CompilerArguments = "/make \"$vbploc\" /out \"$Log\" /outdir \"$TargetPath\"";

if ( $used_vbp_options{'CompatibleMode'} eq '"2"')
{
 $StepDescription =  "\nBuilding Binary Compatible $vbp for " . $Target->get;
}
elsif ( $used_vbp_options{'CompatibleMode'} eq '"1"')
{
 $StepDescription =  "\nBuilding Project Compatible $vbp for " . $Target->get;
}
else
{
 $StepDescription =  "\nBuilding Non-Compatible $vbp for " . $Target->get;
}

# no path cmd otherwise start /wait construction trips
@pieces = split(/[\\\/]/,$vbexe);
$vbcmd = pop(@pieces);
$vbcmd =~ s/"//g; #"

$RC = 0;
omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);
#omlogger('header+begin');
chmod 0777,$Target->get;

`start /wait $vbcmd $CompilerArguments`;

if ( $? != 0 )
{
 $RC = 1;
 $StepError = "$Compiler did not execute properly. ";
# omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepError",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), push(@DeleteFileList,$Target->get), ExitScript("1",@DeleteFileList) if ($RC != 0);
# push(@DeleteFileList,$Log);
# push(@DeleteFileList,@dellist);
# ExitScript($RC,@DeleteFileList);
}

#   If compile was succesful and the user set version info by tgt, env variable, or if auto-increment is turned on,
#   then we write that info back to the source VBP here

elsif ($auto_inc != 0 || $updateVerInfo=="true")
{

 open (NEW, "<$vbploc");  #Take the new temp vbp and write it to an array called @OurNewVBP
 @OurNewVBP = <NEW>;
 close(NEW);

 #Then scan for new version info based upon our variables $auto_inc and $updateVerInfo

 if ($auto_inc != 0 && $updateVerInfo ne 'true')
 {

  foreach (@OurNewVBP)
  {
   $NewRevVer = $1 if ($_ =~ /(RevisionVer\=+\d)/);
  }
  foreach (@NewSource)
  {
   $_ = "$NewRevVer\n" if $_ =~ /(^RevisionVer\=)/;
  }
  push (@CompilerOut,"*Writing auto-incremented Revision Version number to source VBP : $NewRevVer*\n");
 }
 else
 {

  foreach (@OurNewVBP)
  {
   $NewMajVer = $1 if ($_ =~ /(MajorVer\=+\d)/);
   $NewMinVer = $1 if ($_ =~ /(MinorVer\=+\d)/);
   $NewRevVer = $1 if ($_ =~ /(RevisionVer\=+\d)/);
  }
  foreach (@NewSource)
  {
   $_ = "$NewMajVer\n" if $_ =~ /(^MajorVer\=)/;
   $_ = "$NewMinVer\n" if $_ =~ /(^MinorVer\=)/;
   $_ = "$NewRevVer\n" if $_ =~ /(^RevisionVer\=)/;
  }
  push (@CompilerOut, "*Writing user-defined Major Version number to source VBP: $NewMajVer*\n") if ($used_vbp_options{'MajorVer'});
  push (@CompilerOut, "*Writing user-defined Minor Version number to source VBP: $NewMinVer*\n") if ($used_vbp_options{'MinorVer'});

  if ($vb6_env_options{'RevisionVer'} && $auto_inc == 0)
  {
   push (@CompilerOut, "*Writing user-defined Revision Version number to source VBP: $NewRevVer*\n");
  }
  elsif ($vb6_env_options{'RevisionVer'} && $auto_inc != 0)
  {
   push (@CompilerOut, "*Writing user-defined and auto-incremented Revision Version number to source VBP: $NewRevVer*\n");
  }
  elsif ($auto_inc != 0)
  {
   push (@CompilerOut, "*Writing auto-incremented Revision Version number to source VBP: $NewRevVer*\n");
  }
 }

 #-- use with file
 Win32::File::SetAttributes( $vbp, $vbp_attrib & ~READONLY);
 open (NEW_VBP_SOURCE, ">$vbp");
 print NEW_VBP_SOURCE @NewSource; #Write out new source VBP
 close (NEW_VBP_SOURCE);

}

unless ( open(LOG, "<$Log") )
{
 $RC = 1;
 $StepError .= "Could not open log file, '$Log', to determine status of compile.";
# omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepError",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), push(@DeleteFileList,$Target->get), ExitScript("1",@DeleteFileList) if ($RC != 0);
# push(@DeleteFileList,$Log);
# push(@DeleteFileList,@dellist);
# ExitScript($RC,@DeleteFileList);
}

#SBT 12.12.03 - Added code to display vbp to log file on KeepScript

@Log = <LOG>;
close LOG;
$LogMessage = join('', @Log);
push(@CompilerOut,@Log);

#-- The VB command does not return proper codes so we have to parse
#   the error message in every language

$RC = 1 if ($LogMessage !~ /succeeded/ && $LogMessage !~ /erfolgreich/
            && $LogMessage !~ /r.ussi/i
            && $LogMessage !~ /xito/ ); # case 2580 support for French

if ($KeepScript =~ /YES/i)
{
 push(@CompilerOut,"\n****** Begin '$vbploc' ******\n");
 open(LOG, "<$vbploc");
 @Log = <LOG>;
 close LOG;
 push(@CompilerOut,@Log);
 push(@CompilerOut,"****** End '$vbploc' ******\n\n");
}

#SBT 12.12.03 End

$StepError .= "$StepDescription failed!";
omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepError",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), push(@DeleteFileList,$Target->get), ExitScript("1",@DeleteFileList) if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);

print '';  # This is to flush stdout - perl bug?

#-- JAG - delete the vbp that we copied from our omXXXX.vbp
unlink $vbploc;

#-- JAG - if we copied the original vbp, copy it back
if ( $vbpbak )
{
 rename ( $vbpbak, $vbploc);
}

##### Return .vbp file to original attributes ######

print "Applying original attributes to $vbp";
Win32::File::SetAttributes( $vbp, $vbp_attrib);

#get current attributes
#my @newtmp = split(/ /, `attrib \"$vbp\"`);
#pop @newtmp;

#unset current attributes
#foreach $newtmp(@newtmp)
#{
#@attriboutput = `attrib -$newtmp \"$vbp\"`
#}

# reset old attributes
#foreach $origattrib (@origattribs)
#{
#@attriboutput = `attrib +$origattrib \"$vbp\"`
#}

####


push(@DeleteFileList,$Log);
push(@DeleteFileList,@dellist);

ExitScript($RC,@DeleteFileList);

###################################

sub WriteLine
{
 my($keyword, $value) = @_;

 print VBP "$keyword=$value\n";
}

################################################################################

sub Process_Object
{
 my($value) = @_;

 # Syntax:
 #
 #       Object=guid#version#id; filename
 # Object={6B7E6392-850A-101B-AFC0-4210102A8DA7}#1.1#0; comctl32.ocx

 ($guid,$file) = split(/\;/,$value);

 $file =~ s|^\s*||;
 $file = OMSplitPath($file,'fe');
 $file = lc($file);

 # look up filename in OM object hash
 if ($DepList{$file} eq '')
 {
  return("$value");
 }
 else
 {
  return("$guid\;$DepList{lc($file)}");
 }
}

################################################################################

sub Process_Reference
{
    # Syntax:
    #
    #       Reference={guid}#version#id#filename#name
    # Reference=*\G{EF404E00-EDA6-101A-8DAF-00DD010F7EBB}#5.0#0#..\..\vb5\vb5ext.olb#Microsoft Visual Basic Extensibility

 my($value) = @_;
 my($guid, $version, $id, $desc, $filename, $savevalue);

 @fields = split(/\#/,$value);

 $guid    = shift(@fields);
 $version = shift(@fields);
 $id      = shift(@fields);
 $desc    = pop(@fields) if (@fields > 1);
 $filename= join(' ',@fields);
 @sfname  = split(/:/,$filename);
 $cnt     = @sfname;

 if ($filename =~ /[{}-]/ || $cnt > 2)
 {
  $key = "TypeLib\\" . substr($guid,3) . '\\' . $version . '\\' . $id . '\\win32';

  print "Fixing up Reference GUID\n";
  print "   $key\n";

  if ($HKEY_CLASSES_ROOT->Open($key, $NewObj) == 1)
  {
   $NewObj->QueryValue("", $filename);
   print "using $filename for reference\n";
   $value = $guid . '#' . $version . '#' . $id . '#' . $filename . '#' . $desc;
  }
  else
  {
   print "No GUID found for $desc!\n";
  }
 }


 $filename = OMSplitPath($filename,'fe');
 $filename = lc($filename);
 $filename = $DepList{$filename};

 #-- JAG 08.02.04 - Case 4889 - unable to determine why this is here.
 #if ($filename =~ /harref/i)
 #{
 # `copy $filename bin`;
 # $filename = "bin\\" . OMSplitPath($filename,'fe');
 # `regtlb -u $filename`;
 # `regtlb $filename`;
 #}

 # look up filename in OM object hash

 if ($filename eq '')
 {
  return ($value);
 }
 else
 {
  return($guid . '#' . $version . '#' . $id . '#' . $filename . '#' . $desc);
 }
}

#############################################


sub OMSplitPath
{
  my($FullPath,$dpfe) = @_;
  my(@templist,$Drive,$Path,$File,$Ext);

  $FullPath =~ s|\"||g;  # strip out all quotes"
  $FullPath =~ s|^\s*||;
  $FullPath =~ s|\s$||;

  $_ = $FullPath;

  ($Drive,$Path) = split(/:/,$_);

  if ($Path ne '')
  {
   $Drive .= ':';
  }
  else
  {
   $Drive='';
   $Path = $FullPath;
  }

  if ($FullPath =~ /(\.\.\\)+/)
  {

     $FullPath =~ s|(\.\.\\)+||;

  }

  @templist = split(m|\\|,$Path);
  $cnt = @templist;

  if ($cnt > 1)
  {
   $File = $templist[$cnt-1];
   $Ext  = $templist[$cnt-1];

   @templist = @templist[0..$cnt-2];
   $Path  = join('\\',@templist);
   $File  =~ s|\..*$||;
   $Ext   =~ s|^[^.]*||;
  }
  else
  {
   $Path = '';
   $File = $FullPath;
   $Ext  = $FullPath;
   $File  =~ s|\..*$||;
   $Ext   =~ s|^[^.]*||;

  }

 $_ = $dpfe;
 $ompath = '';

 if(/d/i) { $ompath  = $Drive      }
 if(/p/i) { $ompath .= $Path.'\\'  }
 if(/f/i) { $ompath .= $File       }
 if(/e/i) { $ompath .= $Ext        }

 $ompath =~ s|\\$||;
 $ompath = $ompath;

 return( $ompath );
}
