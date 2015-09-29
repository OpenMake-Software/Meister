$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Yacc\040Header\040Generate.sc,v 1.2 2005/06/06 16:18:06 jim Exp $';
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

#-- Note: this script won't work for Bison

use File::Copy;

@CompilersPassedInFlags = ("yacc");
$DefaultCompiler  = "yacc";

($Compiler,$Flags) = GetCompiler("$DebugFlags $DBInfo","$ReleaseFlags $DBInfo",$DefaultCompiler,@CompilersPassedInFlags);

$TargetFile = $Target->get;

$Source = $TargetDeps->getExt(qw(.Y));

#-- Check $Flags for symbol to replace
if ( $Flags =~ s/\byaccreplace\=(\S+)//i ) {
 $YaccSymbol = $1;
 $YaccReplace = 1;

}

#-- Clean up first so we don't pick up an old
# header

chmod 0777, 'ytab.h', 'y.tab.h';
unlink 'ytab.h', 'y.tab.h';

$CompilerArguments = $Flags . " \"$Source\"";

$StepDescription = "Performing Yacc operation for $TargetFile";
omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,"",$RC,@CompilerOut);

@CompilerOut = `$Compiler $CompilerArguments 2>&1`;

$RC = $?;

#----- move header to final resting place

if ( $RC == 0 ) {
 if ( -f 'y.tab.h' ) {
  push @CompilerOut, "\n", "move y.tab.h, $TargetFile", "\n";
 
  unless (move 'y.tab.h', $TargetFile ) {
   push @CompilerOut, "$!";
   $RC = 1;
  }
 } elsif ( -f 'ytab.h' ) {
  push @CompilerOut, "\n", "move ytab.h, $TargetFile", "\n";
 
  unless (move 'ytab.h', $TargetFile ) {
   push @CompilerOut, "$!";
   $RC = 1;
  }
 
 } else {
  $RC = 1;
  push @CompilerOut, "\n", "ERROR: Could not find output file from yacc.", "\n";

 }
}

#-- Now replace symbols inside of Yacc if used
# e.g., perl -pi.bak -e "s/\byy/\Lcmnet_/g; s/\bYY/\Ucmnet_/g;" ../linopt/bascmparse.c ../linopt/ycmtab.h

if ( $YaccReplace and $RC == 0 ) {
 
 unless ( replaceYYSymbols( $TargetFile, $YaccSymbol ) {
  $RC = 1;
 }
 
}


omlogger("Final",$StepDescription,"ERROR:","ERROR: $StepDescription failed!",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut), $RC = 1 if ($RC != 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArguments,$IncludeNL,$RC,@CompilerOut) if ($RC == 0);

#-- Replace symbols inside of Lex file if used.  Original is:
# e.g., perl -pi.bak -e "s/\byy/\Lcmnet_/g; s/\bYY/\Ucmnet_/g;" ../linopt/bascmparse.c ../linopt/ycmtab.h

sub replaceYYSymbols {
 my ($file, $replacement ) = @_;
 
 #-- uses standard perl return convention (0=fail),
 #   not shell convention (0=success)
 
 unless ( move $file, $file . ".bak" ) {
  push @CompilerOut, "\n", "Error creating backup file, $TargetFile" . ".bak", "\n";
  return 0;
 }

 unless (open IF, "<$file" . ".bak" ) {
  push @CompilerOut, "\n", "Error opening backup file for reading, $TargetFile" . ".bak", "\n";
  return 0;
 }
 
 my @lines = <IF>;
 close IF;
 
 #-- open output file under original name
 unless ( open RF, ">$file" ) {
  push @CompilerOut, "\n", "Error opening output file for writing, $TargetFile", "\n";
  return 0;
 }
 
 foreach my $line ( @lines ) {
  $line =~ s/\byy/\L$replacement/g;
  $line =~ s/\bYY/\U$replacement/g;

  print RF $line;
  
 }
  
 close RF;

 #-- we were apparently successful
 return 1;

}
