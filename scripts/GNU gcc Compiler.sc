$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/GNU\040gcc\040Compiler.sc,v 1.3 2005/06/06 16:18:05 jim Exp $';
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

@CompilersPassedInFlags = ("gcc", "g++");
$DefaultCompiler  = "gcc";

($Compiler,$Flags) = get_compiler($DebugFlags,$ReleaseFlags,$DefaultCompiler,@CompilersPassedInFlags);

#-- JAG - 03.03.04 - case 4354 - for certain compilers, can force the "-I-" option
my $ForceIOption = "-I-"; #-- both aCC and cc should support -I-
my $ForceIMsg = '';

#-- print warning
$ForceIMsg = "Using $ForceIOption to override 'include \" \"' headers in local source directories\n\n" 
  if ( $Quiet =~ /no/i );

#-- add this to $IncludeNL so that it's printed in the logging
$IncludeNL = "$ForceIOption\n" . $IncludeNL if ( $ForceIOption);

$TargetFile = $Target->get;
$Source = $TargetDeps->getExtQuoted(qw(.C .CPP .CXX .CC));

$CompilerArguments = "$Flags $Defines -c $Source -o $TargetFile";

$StepDescription   = "Performing GNU C/C++ Compile for $TargetFile";
#-- Due to formatting in 'omlogger', add $ForceIMsg in front of $Compiler
omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.", $ForceIMsg . $Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut);

@CompilerOut = `$Compiler $ForceIOption $Include $CompilerArguments 2>&1`;
$RC = $?;

omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut) if ($RC == 0);

