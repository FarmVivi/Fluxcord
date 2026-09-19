package fr.farmvivi.fluxcord.core.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Resolves plugin dependencies to determine the correct loading order.
 */
public class DependencyResolver {
    private static final Logger logger = LoggerFactory.getLogger(DependencyResolver.class);

    private final Map<String, PluginDescriptor> pluginDescriptors;
    private final Set<String> missingDependencies = new HashSet<>();
    private final Set<String> circularDependencies = new HashSet<>();

    /**
     * Creates a new dependency resolver.
     *
     * @param pluginDescriptors map of plugin names to their descriptors
     */
    public DependencyResolver(Map<String, PluginDescriptor> pluginDescriptors) {
        this.pluginDescriptors = new HashMap<>(pluginDescriptors);
    }

    /**
     * Resolves dependencies and returns the sorted list of plugin names.
     *
     * @return list of plugin names in proper loading order
     */
    public List<String> resolve() {
        // Clear state
        missingDependencies.clear();
        circularDependencies.clear();

        // Build dependency graph
        Map<String, Set<String>> graph = buildDependencyGraph();

        // Perform topological sort
        List<String> result = topologicalSort(graph);

        // Log any issues found
        if (!missingDependencies.isEmpty()) {
            logger.warn("The following plugins have missing dependencies: {}", missingDependencies);
        }

        if (!circularDependencies.isEmpty()) {
            logger.warn("Circular dependencies detected among these plugins: {}", circularDependencies);
        }

        logger.info("Resolved plugin load order: {}", result);
        return result;
    }

    /**
     * Gets plugins with missing dependencies.
     *
     * @return set of plugin names
     */
    public Set<String> getMissingDependencies() {
        return Collections.unmodifiableSet(missingDependencies);
    }

    /**
     * Gets plugins involved in circular dependencies.
     *
     * @return set of plugin names
     */
    public Set<String> getCircularDependencies() {
        return Collections.unmodifiableSet(circularDependencies);
    }

    /**
     * Builds the dependency graph from plugin descriptors.
     *
     * @return mapping of plugin name to its dependencies
     */
    private Map<String, Set<String>> buildDependencyGraph() {
        Map<String, Set<String>> graph = new TreeMap<>();

        // Add all plugins to the graph
        for (String pluginName : pluginDescriptors.keySet()) {
            graph.put(pluginName, new TreeSet<>());
        }

        // Add dependencies to the graph
        for (Map.Entry<String, PluginDescriptor> entry : pluginDescriptors.entrySet()) {
            String pluginName = entry.getKey();
            PluginDescriptor descriptor = entry.getValue();

            // Add hard dependencies
            for (String dependency : descriptor.dependencies()) {
                if (pluginDescriptors.containsKey(dependency)) {
                    graph.get(pluginName).add(dependency);
                } else {
                    missingDependencies.add(pluginName);
                    logger.warn("Plugin {} depends on {} which is not installed", pluginName, dependency);
                }
            }

            // Add soft dependencies (these don't cause a missing dependency warning)
            for (String softDependency : descriptor.softDependencies()) {
                if (pluginDescriptors.containsKey(softDependency)) {
                    graph.get(pluginName).add(softDependency);
                }
            }
        }

        return graph;
    }

    /**
     * Performs a topological sort on the dependency graph.
     *
     * @param graph the dependency graph
     * @return the sorted list of plugin names
     */
    private List<String> topologicalSort(Map<String, Set<String>> graph) {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        // Visit each node (the graph is a TreeMap, so independent plugins come out alphabetically)
        for (String node : graph.keySet()) {
            if (!visited.contains(node)) {
                visit(node, graph, visited, visiting, result);
            }
        }

        // visit() appends a node only after all of its dependencies (post-order), so the list is
        // already in load order: dependencies first, dependants after. Do not reverse it.
        return result;
    }

    /**
     * Helper method for topological sort.
     *
     * @param node     the current node
     * @param graph    the dependency graph
     * @param visited  set of visited nodes
     * @param visiting set of nodes being visited (for cycle detection)
     * @param result   the result list
     */
    private void visit(String node, Map<String, Set<String>> graph,
                       Set<String> visited, Set<String> visiting,
                       List<String> result) {
        // Check for cycles
        if (visiting.contains(node)) {
            circularDependencies.addAll(visiting);
            return;
        }

        // Mark as visiting
        visiting.add(node);

        // Visit all dependencies
        for (String dependency : graph.get(node)) {
            if (!visited.contains(dependency)) {
                visit(dependency, graph, visited, visiting, result);
            }
        }

        // Mark as visited and add to result
        visiting.remove(node);
        visited.add(node);
        result.add(node);
    }
}