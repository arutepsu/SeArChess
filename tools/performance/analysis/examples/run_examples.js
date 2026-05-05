'use strict';

const path    = require('path');
const { analyze, compare } = require('../dist/index');

function load(filename) {
  return require(path.join(__dirname, filename));
}

function printReport(label, report) {
  console.log(`\n${'='.repeat(60)}`);
  console.log(`  ${label}`);
  console.log('='.repeat(60));
  console.log(JSON.stringify(report, null, 2));
}

// Individual reports
const cpuReport         = analyze(load('cpu_bound_input.json'));
const ioReport          = analyze(load('io_bound_input.json'));
const contentionReport  = analyze(load('contention_input.json'));
const unknownReport     = analyze(load('unknown_input.json'));

printReport('CPU_BOUND example',   cpuReport);
printReport('IO_BOUND example',    ioReport);
printReport('CONTENTION example',  contentionReport);
printReport('UNKNOWN example',     unknownReport);

// Comparison: io_bound (baseline) → cpu_bound (optimized, same scenario for illustration)
const comparisonReport = compare(contentionReport, ioReport);
printReport('COMPARISON: contention (baseline) vs io_bound (optimized)', comparisonReport);
