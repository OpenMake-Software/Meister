# Openmake Perl

use Openmake::Snapshot;
$ScriptName = $Script->get;
$ScriptVersion = '$Header: /CVS/openmake64/tomcat/webapps/openmake.ear/openmake.war/scripts/Ant\040Classic\040Javac\040Task.sc,v 1.4 2010/04/13 00:19:54 steve Exp $';
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
  
 $ScriptVersion = "$file : $module, v$version";
}

#########################################
#-- Load Openmake Variables from Data File
#   Uncomment Openmake objects that need to be loaded

#  @PerlData = $AllDeps->load("AllDeps", @PerlData );
#  @PerlData = $RelDeps->load("RelDeps", @PerlData );
@PerlData = $NewerDeps->load("NewerDeps", @PerlData );
@PerlData = $TargetDeps->load("TargetDeps", @PerlData );
@PerlData = $TargetRelDeps->load("TargetRelDeps", @PerlData );
#  @PerlData = $TargetSrcDeps->load("TargetSrcDeps", @PerlData );

$ScriptHeader = "Beginning Ant Task Javac...";
$ScriptFooter = "Finished Ant Task Javac";
$ScriptDefault = "Initial";
$ScriptDefaultHTML = "Initial";
$ScriptDefaultTEXT = "Initial";
$HeaderBreak = "True";

$StepDescription = "Java Compile for $E{$FinalTarget->get}";
#omlogger( 'header');
$Verbose = 1 if $Quiet =~ /no/i;

$omdebug = 1 if $ENV{OMDEBUG} ne '';

#-- Deal with calling Ant, first
$Compiler = GetAnt();

$DefaultFlags = '';
$CompilerArgs  = $DefaultFlags;
$CompilerArgs .= $DebugFlags   if ($ENV{CFG} eq 'DEBUG');
$CompilerArgs .= $ReleaseFlags if ($ENV{CFG} ne 'DEBUG');

#-- Generate Bill of Materials if requested
GenerateBillofMat($BillOfMaterialFile->get,$BillOfMaterialRpt,$Target->get)
 if( defined $BillOfMaterialFile );

#-- Deal with the compiler that ant will call
($CompilerArgs, $TaskArgs) =  ProcessAntFlags( $CompilerArgs, 'javac' );

#-- Get classpath from Classpath Task
$ClassPath = GetClasspath( $TargetDeps );

#-- fixes for case 1793, jikes support
if ( $BuildType =~ /jikes/i ) {
 delete $ENV{JIKESPATH};
 $CompilerArgs .= " -Djikes.class.path=$ClassPath";
}

#-- Handle case where destdir is passed in as a define and not already specified in tgt file
if ($TaskArgs !~ /destdir\=/){
  if (   $Defines =~ /destdir\s*\=\s*\"([^\"]*)\"/ ) {
    $TaskArgs = "destdir=\"$E{$IntDir->get}$DL$1\" $TaskArgs";
  
  } elsif ( $TaskArgs =~ /destdir\s*\=\s*\"([^\"]*)\"/i ) {
    $TaskArgs = "destdir=\"$E{$IntDir->get}$DL$1\" $TaskArgs";
  
  } else {
    $TaskArgs = "destdir=\"$E{$IntDir->get}\" $TaskArgs";
  }
}

#-- Format source and classpath lines
$ClassPathNL = " " . $ClassPath;
$ClassPathNL =~ s/$PathDL/\n /g;

$ClassPathNL = "\nSourcepath:\n " . join("\n ", $VPath->getList)
                . "\n\nClasspath:$ClassPathNL"
                if $Verbose;

#-- Format task args 
if( $TaskArgs and $Verbose ) {
 $ClassPathNL = "Ant Javac Task Attributes: $TaskArgs\n" . $ClassPathNL;
}

#-- Call copy java local, populates 2 lists:
@Source = CopyLocal( $TargetDeps, $TargetRelDeps, $IntDir->get, '.java' );

#-- determine packages based on the above source code
@Packages  = GetPackages( @Source );

foreach ( @Packages ) {
 if( /\.java$/ ) {
  push(@JavacIncludeLines, GetAntIncludeXML( $_ ))
  
 } else {
  $_ .= "$DL*.java";
  push(@JavacIncludeLines, GetAntIncludeXML( $_ ))
  
 }
}

#-- Build list of file globs to delete, to clean up inner classes
if ( $NewerDeps->getList ne () ) {
 # Take 'before' file snapshot
 $preSnapshot = Openmake::Snapshot->new( $IntDir->get, '.class' );
 
 # Get a list of all classes that exist prior to doing anything
 %files_timestamps = $preSnapshot->get('.class');
 @BuildDirClasses = keys %files_timestamps;
}

# Get a list of newer java files presented relative to
# the build directory (strip of search path part):
@NewerClasses = GetOutOfDateClasses( $NewerDeps, $TargetRelDeps );

foreach $NewerClass (@NewerClasses ) {
 
 foreach $BuildDirClass ( @BuildDirClasses ) {
   #-- escape this for pattern matching
   if ( $IntDir->get eq '.' ) {
    $eNewerClass = $NewerClass;
    
   } else {
    $eNewerClass = $IntDir->get . $DL . $NewerClass;
    
   }
   
   #-- change windows slashes to unix-perl like since @BuildDirClasses
   #-- comes from a perl function, the slashes need to match
   $eNewerClass =~ s/\\/\//g;
   $eNewerClass =~ s/(\W)/\\$1/g;
   
   if ( $BuildDirClass =~ /^$eNewerClass$/ ) {
    # We found a match between a class that exists
    # and a class to be recompiled
    $OutOfDateClassGlob = $BuildDirClass;
    last;
   }
 }
 
 # Now change the out-of-date class name to a glob
 # to include all inner classes as well.
 $OutOfDateClassGlob =~ s/\.class$/\*.class/;
 
 if( $OutOfDateClassGlob ) {
  @OutOfDateClassFiles = glob $OutOfDateClassGlob;
  foreach $file (@OutOfDateClassFiles) { $DelClassNL .= " $file\n" }
   
  chmod 0777, @OutOfDateClassFiles;
  unlink @OutOfDateClassFiles;

 } else {
  @OutOfDateClassFiles = ();
 }

}

#-- Format the Output for omlogger()
if ( $DelClassNL ) {
 $ClassPathNL .= "\n\nDeleting Out-Of-Date classes:\n\n";
 $ClassPathNL .= $DelClassNL;
}

#-- Build list of existing class files that are current
#-- Must be done after deleting classes associated with
#-- newer dependencies
foreach $package (@Packages ) {
 if ( $package =~ /\.java/ ) {
  $package =~ s/\**\.java$/\*.class/;
  
 } else {
  $package .= "$DL*.class"
  
 }
 
 @PackageClasses = glob $IntDir->get.$DL.$package;

 push @CurrentClasses, @PackageClasses;
 
}


#-- print to stdout, begin of compile operation
omlogger("Begin",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArgs,$ClassPathNL,$RC,@CompilerOut);

#-- create build.xml file
WriteAntXML( &getAntXML );

# Take 'before' file snapshot
$beforeSnapshot = Openmake::Snapshot->new( $IntDir->get, '.class' );

# Execute Ant
@CompilerOut = `$Compiler $CompilerArgs 2>&1`;
$RC = $?;

# Take After Snapshot
$afterSnapshot = Openmake::Snapshot->new( $IntDir->get, '.class' );

# Write output for case compile failed.
$RC = 1 if ($RC != 0);

if ( $RC == 1 ) {
 #-- compile failed completely, ant didn't run at all
 $StepError = 'Ant Javac Task.sc: 00: Ant could not be executed.';
 omlogger("Final",$StepDescription,"FAILED",$StepError,$Compiler,$CompilerArgs,"",$RC,@CompilerOut);
 
} elsif ( grep( /FAILED/, @CompilerOut ) ) {
 #-- Ant finished running, we could still get a compile error
 $RC = 1;
 $StepError = "Ant Javac Task.sc: 01: Task Failed.\n" ;
 omlogger("Final",$StepDescription,"FAILED",$StepError,$Compiler,$CompilerArgs,"",$RC,@CompilerOut);
 unlink $Target->get;
 
 } else {
 #-- Ant finished runnning, no 'BUILD FAILED' report
 $RC = 0;
 # Compare snapshots
 @Classes = LeftSnapshotOnly( $afterSnapshot, $beforeSnapshot );

 # Ensure @Classes contains only classes
 @Classes = grep( /\.class$/, @Classes);

 if ( $Verbose ) {
  push( @Footer, "\n","Compiled the following classes:\n","\n" );
  foreach ( sort @Classes ) { push( @Footer, " $_\n") }

  push( @Footer, "\n","The following classes are Up-To-Date:\n","\n" )
   unless @CurrentClasses == ();
  foreach ( sort @CurrentClasses ) { push( @Footer, " $_\n") }
  push(@CompilerOut, @Footer, "\n");
 }

 #-- create packages file to be used by Jar task and others
 unless ( open(RSP, ">$E{$Target->get}") ) {
  $StepError = "Ant Javac Task.sc: 02: Couldn't open $E{$Target->get}.\n" ;
  omlogger('Final',$StepDescription,"FAILED","ERROR: $StepError","","","",1,"");
 }

 foreach (sort @Classes, @CurrentClasses) { 
  print RSP "$_\n";
 }
 close RSP;

 omlogger("Final",$StepDescription,"ERROR:","$StepDescription succeeded.",$Compiler,$CompilerArgs,"",$RC,@CompilerOut);
}

push @dellist, 'build.xml' unless $KeepScript =~ /y/i; 
ExitScript $RC, @dellist;

##################################################
sub getAntXML {

 my $xml =<<ENDXML;
 project name = "$Project" default = "compile" basedir = "."

 !-- Set properties --
  !--   ignore the classpath that Ant is running under --
  property name = "build.sysclasspath" value = "ignore" /

  property name = "src" value = "$E{$IntDir->get}" /
  property name = "build" value = "$E{$IntDir->get}" /

  target name = "init"
   mkdir dir = "\${build}" /
  /target

 !-- Start compile section --
 target  name = "compile" depends = "init"
  javac srcdir = "\${src}" classpath = "$ClassPath" $TaskArgs 
  @JavacIncludeLines
  /javac
 /target

 !-- End the project --
 /project
ENDXML

 return $xml
}

sub GetOutOfDateClasses {
 my $NewerDeps = shift;  # Openmake::FileList object
 my $TargetRelDeps = shift;      # Openmake::FileList object

 my ($file, $Tmp, @localfiles, $TargetJava);
 my @localfiles = ();
 my @NewerJavaList, @LocalJavaList;
 
 push( @NewerJavaList, grep( /java$/, $NewerDeps->getList ) );
 push( @LocalJavaList, grep( /java$/, $TargetRelDeps->getList ) );
  
 # Now get the relative name of the java file
 foreach $NewerJavaFile (@NewerJavaList) {
  foreach $Tmp (@LocalJavaList)  {
   my $MatchTargetJava = $Tmp;
   $MatchTargetJava =~ s/\\/\\\\/g;  # just in case
   $MatchTargetJava =~ s/\./\\\./g;
   $MatchTargetJava =~ s/\$/\\\$/g;

   $TargetJava = $Tmp, last if ($NewerJavaFile =~ /$MatchTargetJava$/);
  }

  $TargetJava =~ s/\.java$/.class/;
  push(@localfiles, $TargetJava);
 }
 
 return (@localfiles);
}
