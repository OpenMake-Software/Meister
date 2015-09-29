$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/InstallShield.sc,v 1.3 2005/06/06 16:18:05 jim Exp $';
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

$ENV{PATH} = $ENV{INSTALLSHIELD_HOME} . "\\program;" . $ENV{PATH};

### Perform Compile of setup.rul ###

@CompilersPassedInFlags = ("compile");
$DefaultCompiler  = "compile";

($Compiler,$Flags) = get_compiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);

$TargetFile = $Target->get;
$Source = $TargetDeps->getExtQuoted(qw(.rul));
unless ( -e $Source ) {
 omlogger("Final",$StepDescription,"ERROR:","ERROR: Could not find .rul file $Source for build","","","",$RC,@CompilerOut), $RC = 1, ExitScript($RC,qw()) if ($RC != 0);
}

#-- Case 3238 -- added "-I" and check on INSTALLSHIELD_HOME
unless ( $ENV{INSTALLSHIELD_HOME} )
{
 omlogger("Final",$StepDescription,"ERROR:","ERROR: Environment variable INSTALLSHIELD_HOME not set","","","",$RC), $RC = 1, ExitScript($RC,qw()) if ($RC != 0);
}

$CompilerArguments = "-I\"$ENV{INSTALLSHIELD_HOME}\\Include\" $Source";

$StepDescription   = "Performing Installshield Compile for $TargetFile";
omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);

@CompilerOut = `$Compiler $CompilerArguments 2>&1`;
$RC = $?;

omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), $RC = 1, ExitScript($RC,qw()) if ($RC != 0);

### Perform ISBuild to create install package ###

@CompilersPassedInFlags = ("ISBuild");
$DefaultCompiler  = "ISBuild";
#$Source = Openmake::File->new($TargetDeps->getExtQuoted(qw(.ipr)));
#-- fix for case 3238
$Source = Openmake::File->new($TargetDeps->getExt(qw(.ipr)));
unless ( -e $Source ) {
 omlogger("Final",$StepDescription,"ERROR:","ERROR: Could not find .ipr file $Source for build","","","",$RC), $RC = 1, ExitScript($RC,qw()) if ($RC != 0);
}

($Compiler,$Flags) = get_compiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);

$CompilerArguments = "$Defines -p\"" . $Source->getDP . "\"";

$StepDescription   = "Performing ISBuild for $TargetFile";
omlogger("Intermediate",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);

@CompilerOut = `$Compiler $CompilerArguments 2>&1`;
$RC = $?;

omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,"",$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut) if ($RC == 0);



