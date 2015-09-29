# Openmake Perl
$Compiler  = 'Openmake ClassPath Generator';
#-- Set up HTML logging variables
$ScriptName = $Script->get;
$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Set\040Classpath.sc,v 1.3 2005/06/06 16:18:06 jim Exp $';
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

$ScriptHeader = "Beginning Classpath Generation...";
$ScriptFooter = "Finished Classpath Generation";
$ScriptDefault = "Initial";
$ScriptDefault = "Initial";
$ScriptDefaultHTML = "Initial";
$ScriptDefaultTEXT = "Initial";
$HeaderBreak = "True";

$StepDescription = "Classpath Generation for $E{$FinalTarget->get}";

$Flags = $DebugFlags   if ($CFG eq 'DEBUG');
$Flags = $ReleaseFlags if ($CFG ne 'DEBUG');

$ClassPath = Openmake::SearchPath->new;
$ClassPath->push( '.' );
$ClassPath->push( $IntDir->get ) unless $IntDir->get eq '.';
$ClassPath->push( unique($TargetDeps->getExtList(qw(.jar .zip .properties .class ))) );

# Verify that dependencies can be found
@DepSearchFound = ();
@DepSearchMissing = ();
@DepList = unique($TargetDeps->getExtList(qw(.jar .zip .properties .class )));
foreach $dep (@DepList)
{
 if (-f $dep) 
 {
  push(@DepSearchFound, "  $dep\n");
 }
 else
 {
  push(@DepSearchMissing, "  $dep\n");
 }
}

#-- JAG - 03.09.04 - Case 4450. This line doubles up all jars in the classpath
#                    (see line 
#$ClassPath->push( @DepList );

#-- JAG - 03.09.04 - need to see if add.dirs was passed as an option
my $optionref = $ReleaseFlags;
$optionref = $DebugFlags if ( $CFG eq "DEBUG" );

#-- create the option objects
use Openmake::BuildOption;
my $build_option = Openmake::BuildOption->new($optionref);

#-- get the options
my $options_str = $build_option->getBuildTaskOptions( $BuildTask);
$ClassPath->push( $ProjectVPath->getList, $VPath->getList ) 
  if ( $options_str =~ /add.dirs/ );

# Construct formatted classpath for logging
$ClassPathNL = "Classpath:\n\n " . join("\n ", $ClassPath->getList );
$ClassPathNL .= "\n";

unless ( open(CP, ">$E{$Target->get}") ) {
 $StepError = "classpath.sc: 01: Couldn't open $E{$Target->get}.";
 omlogger('Final',$StepDescription,"FAILED","ERROR: $StepError","","","",1,"");
}

print CP $ClassPath->get . "\n";
close CP;

#-- Generate Bill of Materials if Requested
GenerateBillofMat($BillOfMaterialFile->get,$BillOfMaterialRpt,$Target->get)
 if( defined $BillOfMaterialFile );

$RC = 0;
if (@DepSearchMissing) {
  $RC = 1;
  push(@CompilerOut, "ERROR: Dependencies could not be found\n", "\n");
  push(@CompilerOut, "MISSING DEPENDENCIES:\n", @DepSearchMissing, "\n");
  push(@CompilerOut, "Found Dependencies:\n", @DepSearchFound, "\n");
  push @dellist, $Target->get;
}
else {
  push(@CompilerOut,$ClassPathNL);
}

omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArgs,$ClassPathNL,$RC,@CompilerOut) if ($RC == 0);
omlogger("Final",$StepDescription,"ERROR:","$StepDescription failed.",$Compiler,$CompilerArgs,$ClassPathNL,$RC,@CompilerOut) if ($RC == 1);

ExitScript $RC;

###################################################
sub OrderClasspath {
 #-- The following works for directories and .zip
 #-- files as well as .jar files

 my @unorderedJars = @_;
 my $jar, $mJar, @theJarOrder, @orderedJars;
 
 #-- Open up java-order file
 #unless( open(JOR,"<" . $JOR->get ) ) {
 # $StepError = "classpath.sc: 02:  Couldn't open Java Order file, " . $JOR->get . ".\n";
 # write_text_output( 'abort' );
 # omlogger();
 #}
 
 #@theJarOrder = <JOR>;
 #close JOR;
 chomp @theJarOrder;
 
 # make an exception for '.' and the intermediate directory
 @temp = grep(/^\.$/,@unorderedJars);
 $eIntdir = $IntDir->getEscaped;
 @temp2 = grep(/^$eIntdir$/,@unorderedJars);
 
 $dot = shift @temp;
 @unorderedJars = grep(!/^\.$/,@unorderedJars);
 @unorderedJars = grep(!/^$eIntdir$/,@unorderedJars);

 foreach $jar ( @theJarOrder ) {
  next if $jar =~ /^\#|^\s*$/;
  $mJar = $jar;
  $mJar =~ s|(\W)|\\$1|g;
  
  # match from the end since the jar from the
  # unordered list is stuck to a fully qualified path
  # determined by the vpath
  
  push(@orderedJars, grep( /$mJar$/,@unorderedJars ) );
  @unorderedJars = grep( !/$mJar$/,@unorderedJars );
 }

 # put the intermediate output directory back
 unshift(@orderedJars, $eIntdir) if $IntDir->get ne '';
 
 # put the dot back
 unshift @orderedJars, $dot;

 print "end sub OrderJars\n" if $debug =~ /sub/; 
 return (@orderedJars, @unorderedJars);
}

