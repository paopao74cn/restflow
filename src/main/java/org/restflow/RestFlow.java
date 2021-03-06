package org.restflow;

import static java.util.Arrays.asList;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

import org.apache.commons.io.FileUtils;
import org.restflow.data.Uri;
import org.restflow.directors.PublishSubscribeDirector;
import org.restflow.exceptions.WorkflowMissingInputException;
import org.restflow.metadata.FileSystemMetadataManager;
import org.restflow.metadata.RunMetadata;
import org.restflow.reporter.MultiRunReporter;
import org.restflow.reporter.Reporter;
import org.restflow.reporter.ReporterUtilities;
import org.restflow.util.ClassPathHacker;
import org.restflow.util.PortableIO;
import org.restflow.util.TestUtilities;
import org.yaml.snakeyaml.Yaml;


public class RestFlow {
	
	public final static boolean DEBUG = false;

	public static void main(String[] args) throws Exception {
		loadAndRunWorkflow(args);
	}

	public static RunMetadata loadAndRunWorkflow(String[] args) throws Exception {

		enableLog4J();
		
		OptionParser parser = parseOptions();

		WorkflowRunner.Builder wrb = new WorkflowRunner.Builder();
		
		try {

			OptionSet options = parser.parse(args);

			if (options.has("h")) {
				parser.printHelpOn(System.out);
				return null;
			}
					
			if (options.has("cp")) {
				Collection<?> fileNames = options.valuesOf("cp");
				for(Object file : fileNames ) {
				  ClassPathHacker.addFile((String)file);
				}
			}
		
			String runDirectoryContainer = buildRunDirectoryContainer(options);
			
			if (options.has("report") ) {
				String reportName = (String)options.valueOf("report");
				if ( ! options.has("run")) {
					//multi-run reporter
					MultiRunReporter wf = new MultiRunReporter( runDirectoryContainer,reportName);
					wf.renderReport();
				} else {
					//specific run reporter
					String runName = (String)options.valueOf("run");
					
					String runDirectory = runDirectoryContainer + "/" + runName;
					
					RunMetadata metadata = FileSystemMetadataManager.restoreMetadata(runDirectory);
					Reporter reporter = ReporterUtilities.loadReporterFromRunDirectory( runDirectory, reportName);
					reporter.decorateModel( metadata  );
					reporter.renderReport();
				}
				return null;
			}

			String workflowDefinitionResource = null;
			
			// path to a workflow definition resource can be provided as a single (non-option) argument 
			// or as an argument to the -f option
			if (options.nonOptionArguments().size() == 1) {
				workflowDefinitionResource = options.nonOptionArguments().get(0);
			} else if (options.hasArgument("f") && (!options.valueOf("f").equals("-"))) {
				workflowDefinitionResource = (String) options.valueOf("f");
			}

			// runner builder is configured to take the workflow defintion either from a
			// provided workflow definition resource or from the standard input stream
			if (workflowDefinitionResource != null) {
				if (workflowDefinitionResource.contains(":")) {
					wrb.workflowDefinitionPath(workflowDefinitionResource);
				} else {
					wrb.workflowDefinitionPath("file:" + workflowDefinitionResource);
				}
			} else {
				wrb.workflowDefinitionStream(System.in);
			}
				
			
			Map<String,String> resourceMap = new HashMap<String, String>();
			
			resourceMap.put("workspace", resolveWorkspaceLocation(options, workflowDefinitionResource));
			resourceMap.put("actors", "classpath:/org/restflow/java/");	
			
			Collection<?> importMap = options.valuesOf("import-map");
			for (Object pairObj : importMap) {
				String pair = (String) pairObj;
				String[] kv = pair.split("=");
				if (kv.length != 2) {
					throw new Exception(
							"Input options should be key-value pairs. Example: -importMap actors=classpath:/org/restflow/groovy");
				} else {
					resourceMap.put(kv[0], kv[1]);
				}
			}
			
			wrb.importSchemeResourceMap(resourceMap);
			
			if (options.has("w")) {
				wrb.workflowName((String) options.valueOf("w"));
			}
			
			if (options.has("run")) {
				wrb.runName((String) options.valueOf("run"));
			}
			
			if (options.has("prevrun")) {
				wrb.prevRunName((String) options.valueOf("prevrun"));
			}
			
			
			HashMap<String, Object> inputMap = new HashMap<String, Object>();
			
			//load all of the yaml files and bind their contents to the workflow inputs
			Yaml yaml = new Yaml();
			yaml.setBeanAccess(org.yaml.snakeyaml.introspector.BeanAccess.FIELD);
			Collection<?> inputFiles = options.valuesOf("infile");
			for (Object inputFile : inputFiles) {
				Map<String,Object> inputObjects = (Map<String,Object>)yaml.load(FileUtils.readFileToString(new File((String)inputFile))); 
				inputMap.putAll(inputObjects);
			}			
			
			//get values from the command line and bind them to the workflow inputs
			for (Object inputOptionObject :  options.valuesOf("i")) {
				String inputOption = (String) inputOptionObject;
				int indexOfFirstEquals = inputOption.indexOf("=");
				if (indexOfFirstEquals == -1) {
					throw new Exception(
						"Input options should be key-value pairs separated by equal signs. Example: -i count=12");
				} else {
					String inputName = inputOption.substring(0, indexOfFirstEquals);
					String inputValueString = inputOption.substring(indexOfFirstEquals + 1);
					Object inputValue = yaml.load(inputValueString);
					inputMap.put(inputName, inputValue);
				}
			}
			wrb.inputBindings(inputMap);
			
			HashMap<String, Object> outputMap = new HashMap<String, Object>();
			for (Object outputOptionObject : options.valuesOf("o")) {
				String outputOption = (String) outputOptionObject;
				int indexOfFirstColon = outputOption.indexOf(":");
				String outputName = null;
				Object outputPath = null;
				if (indexOfFirstColon == -1) {
					outputName = outputOption;
					outputPath = "-";
				} else {
					outputName = outputOption.substring(0, indexOfFirstColon);
					outputPath = outputOption.substring(indexOfFirstColon + 1);	
				}
				if (outputPath.equals("-") && outputMap.containsValue("-")) {
					throw new Exception("Only one output may be sent to stdout.");
				}
				outputMap.put(outputName, outputPath);
			}
			wrb.outputBindings(outputMap);
			
			wrb.runsDirectory(runDirectoryContainer);			
			
			if (options.has("to-dot")) {
				PublishSubscribeDirector d = new PublishSubscribeDirector();
				wrb.topDirectorOverride(d);
				String dot = wrb.build().generateDot();
				System.out.println(dot);
				return null;
			}
			

			
			if (options.has("t")) {
				wrb.traceReport( true );
			}
			
			if (options.has("v")) {
				wrb.validateOnly(true);
			}
			
			wrb.cutTerminalConnectionsAfterPreamble(options.has("daemon"))
				.suppressWorkflowStdout(options.has("daemon"));
			
			
		} catch (OptionException e) {
			System.err.print("Error in command-line options: ");
			System.err.println(e.getLocalizedMessage());
			parser.printHelpOn(System.err);
		}
		
		WorkflowRunner runner = wrb.build();

		try {
			runner.run();
		} catch (WorkflowMissingInputException e) {
			System.out.println("ERROR: Missing workflow input '" + e.getMissingInput() + 
					"'.  Complete signature for workflow " + e.getWorkflowName() + ":");

			runner.printWorkflowInputs();
			System.exit(0);
		}
		
		return runner.getRunMetaData();
	}

	/**
	 *  If not specified, determine the workflow's workspace directory for importing more files.
	 *  
	 * @param options
	 * @param beanDefinitionResource
	 * @throws IOException 
	 */
	private static String resolveWorkspaceLocation(OptionSet options,
			String beanDefinitionResource) throws IOException {

		if (options.has("workspace")) {
			return "file:" + (String) options.valueOf("workspace");
		} else if (beanDefinitionResource != null) {
			return Uri.extractParent(beanDefinitionResource);
		} else {
			return "file:" + PortableIO.getCurrentDirectoryPath();
		}
	}
	
	private static String buildRunDirectoryContainer(OptionSet options) {
		String runDirectoryContainer;
		if (options.has("base")) {
			
			runDirectoryContainer = (String) options.valueOf("base");
		
			if (runDirectoryContainer.equals("RESTFLOW_TESTRUNS_DIR")) {
				runDirectoryContainer = TestUtilities.getTestRunsDirectoryPath();
			}
			
		} else { 
			
			runDirectoryContainer = System.getenv("RESTFLOW_BASE");
			
			if (runDirectoryContainer == null) {
				runDirectoryContainer = System.getProperty("user.dir");
			}
		}
		return runDirectoryContainer;
	}
	
	public static void enableLog4J() {

		if (!new File("log4j.properties").exists()) {
			if (System.getProperty("org.apache.commons.logging.Log") == null) {
				System.setProperty("org.apache.commons.logging.Log",
						"org.apache.commons.logging.impl.SimpleLog");
			}

			if (System
					.getProperty("org.apache.commons.logging.simplelog.defaultlog") == null) {
				System.setProperty(
						"org.apache.commons.logging.simplelog.defaultlog",
						"error");
			}
		}
	}
	
	private static OptionParser parseOptions() {
		
		OptionParser parser = null;
		
		try {
			parser = new OptionParser() {
			{
				acceptsAll(asList("h", "?"), "show help");
				acceptsAll(asList("v", "validate"), "validate the workflow without running it");
				acceptsAll(asList("t", "enable-trace"), "enable trace");
				acceptsAll(asList("daemon"), "Daemonize after header printed");
				acceptsAll(asList("workspace"), "workspace folder")
						.withRequiredArg().ofType(String.class)
						.describedAs("directory");
				acceptsAll(asList("run"), "forces run name")
				.withRequiredArg().ofType(String.class)
				.describedAs("name");
				acceptsAll(asList("prevrun"), "uses final state from previous run")
				.withRequiredArg().ofType(String.class)
				.describedAs("run directory");	
				acceptsAll(asList("preamble"), "preamble template path")
				.withRequiredArg().ofType(String.class)
				.describedAs("directory").defaultsTo("");
				acceptsAll(asList("report"), "run report on existing run directory")
				.withRequiredArg().ofType(String.class)
				.describedAs("name").defaultsTo("");			
				acceptsAll(asList("base"), "base working directory")
						.withRequiredArg().ofType(String.class)
						.describedAs("directory");
				acceptsAll(asList("f", "workflow-description"))
						.withRequiredArg().ofType(String.class)
						.describedAs("file")
						.defaultsTo("-")
						;
				acceptsAll(asList("w", "workflow"), "workflow name")
						.withRequiredArg().ofType(String.class)
						.describedAs("name");
				acceptsAll(asList("i", "input"), "key-valueed inputs")
						.withRequiredArg().describedAs("input parameters")
						.ofType(String.class).describedAs("key=value");
				acceptsAll(asList("o", "output"), "where to save a workflow output")
						.withRequiredArg().describedAs("workflow output destination")
						.ofType(String.class).describedAs("outputName:outputPath");
				acceptsAll(asList("infile", "input-file"), "yaml input file")
						.withRequiredArg().describedAs("file of input parameters")
						.ofType(String.class).describedAs("file");
				acceptsAll(asList("outfile", "output-file"), "yaml output file")
				.withRequiredArg().describedAs("file of output objects")
				.ofType(String.class).describedAs("file");					
				acceptsAll(asList("to-dot"), "output a Graphviz dot file instead of running the workflow");
				acceptsAll(asList("cp"), "add to classpath")
				        .withRequiredArg().describedAs("jar|directory")
				        .ofType(String.class);
				acceptsAll(asList("import-map"), "key-valueed import scheme to file resource")
		        .withRequiredArg().describedAs("scheme=resource")
		        .ofType(String.class);				
				}
			};
			
		} catch (OptionException e) {
			System.err.print("Option contains illegal character: ");
			System.err.println(e.getLocalizedMessage());
			System.exit(0);
		}
			
		return parser;
	}
}
