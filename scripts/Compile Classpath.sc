########################################
#
# This script is an AmFam custom build-type to combine multiple
# `.classpath` files into one.
#
# The script is based off of OpenMake's `Set Classpath.sc`
#
# Note: Due to OpenMake's idea of a **modular architechture**, this script
# cannot `use strict` or `use warnings`.
#
########################################

########################################
# STANDARD OPENMAKE STUFF
########################################

# Openmake Perl
$Compiler = 'AmFam ClassPath Generator';

#-- Set up HTML logging variables
$ScriptName    = $Script->get;
$ScriptVersion = 'AmFam_Compile_Classpath, v1.0';

#########################################
#-- Load Openmake Variables from Data File
#   Uncomment Openmake objects that need to be loaded

#  @PerlData = $AllDeps->load("AllDeps", @PerlData );
#  @PerlData = $RelDeps->load("RelDeps", @PerlData );
#  @PerlData = $NewerDeps->load("NewerDeps", @PerlData );
@PerlData = $TargetDeps->load( "TargetDeps", @PerlData );
#  @PerlData = $TargetRelDeps->load("TargetRelDeps", @PerlData );
#  @PerlData = $TargetSrcDeps->load("TargetSrcDeps", @PerlData );

$ScriptHeader      = "Beginning Classpath Generation...";
$ScriptFooter      = "Finished Classpath Generation";
$ScriptDefault     = "Initial";
$ScriptDefault     = "Initial";
$ScriptDefaultHTML = "Initial";
$ScriptDefaultTEXT = "Initial";
$HeaderBreak       = "True";

$StepDescription = "Classpath Generation for $E{$FinalTarget->get}";

$Flags = $DebugFlags   if ( $CFG eq 'DEBUG' );
$Flags = $ReleaseFlags if ( $CFG ne 'DEBUG' );
#########################################

#########################################
# INIT CLASSPATH
#########################################

$ClassPath = Openmake::SearchPath->new;
$ClassPath->push('.');
$ClassPath->push( $IntDir->get ) unless $IntDir->get eq '.';

#########################################
# GET ALL DEPENDENCIES
#########################################
my @cp_all_deps;
my @cp_found_deps;
my @cp_missing_deps;

foreach my $_dep (
    unique(
        $TargetDeps->getExtList(qw(.jar .zip .properties .class .classpath ))
    )
  )
{
    if ( $_dep =~ m{[\w\s]+\.classpath$}xi ) {
        push @cp_all_deps, _get_classpath_entries($_dep);
    }
    else {
        push @cp_all_deps, $_dep;
    }
} ## end foreach my $_dep ( unique( ...))

foreach my $_dep (@cp_all_deps) {
    if   ( -e $_dep ) { push @cp_found_deps,   $_dep; }
    else              { push @cp_missing_deps, $_dep; }
} ## end foreach my $_dep (@cp_all_deps)

#########################################
# SAVE FOUND DEPENDENCIES
#########################################

# Save
$ClassPath->push(@cp_found_deps);

# Print
my $_cp_log_line = "Classpath:\n\n" . join( " \n", @cp_found_deps );

# Check for missing deps
if (@cp_missing_deps) {
    $_cp_log_line .= "Missing:\n\n" . join( " \n", @cp_missing_deps );
    omlogger(
        "Final",
        $StepDescription,
        "ERROR:",
        "$StepDescription failed.",
        $Compiler,
        "",
        $_cp_log_line,
        1,
        "ERROR: Dependencies could not be found\n\n",
        $_cp_log_line
    );
    ExitScript( 1, () );
} ## end if (@cp_missing_deps)

#########################################
# SAVE CLASSPATH
#########################################

unless ( open( CP, ">$E{$Target->get}" ) ) {
    $StepError = "classpath.sc: 01: Couldn't open $E{$Target->get}.";
    omlogger( 'Final', $StepDescription, "FAILED", "ERROR: $StepError",
        "", "", "", 1, "" );
    ExitScript( 1, () );
} ## end unless ( open( CP, ">$E{$Target->get}"...))

print CP $ClassPath->get . "\n";
close CP;

#-- Generate Bill of Materials if Requested
GenerateBillofMat( $BillOfMaterialFile->get, $BillOfMaterialRpt,
    $Target->get )
  if ( defined $BillOfMaterialFile );

#########################################
# EXIT
#########################################
omlogger( "Final", $StepDescription, "INFO:", "$StepDescription succeeded.",
    $Compiler, "", $_cp_log_line, 0, $_cp_log_line );
ExitScript( 0, () );

########################################
# INTERNAL SUBROUTINES
########################################
sub _get_classpath_entries {
    my ($cpf) = @_;
    my @entries;

    if ( open( my $_CPF, '<', $cpf ) ) {

        while ( my $_line = <$_CPF> ) {
            chomp($_line);
            foreach my $_entry ( split( /\s*$PathDL\s*/, $_line ) ) {
                if ( $_entry =~ m{[\w\s]+\.classpath$}xi ) {
                    push @entries, _get_classpath_entries($_entry);
                }
                else {
                    push @entries, $_entry;
                }
            } ## end foreach my $_entry ( split(...))
        } ## end while ( my $_line = <$_CPF>)
    } ## end if ( open( my $_CPF, '<'...))
    else {
        # Failed to read file
        my $_err
          = "ERROR: ReadClasspathFile: Couldn't open .classpath file: '$dep'.\n";
        omlogger( "Final", "ReadClasspathFile", "ERROR:", $_err, $Compiler,
            "", "", 1, $_err );
        ExitScript( 1, () );
    } ## end else [ if ( open( my $_CPF, '<'...))]

  return @entries;
} ## end sub _get_classpath_entries

