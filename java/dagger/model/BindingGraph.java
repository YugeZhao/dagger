/*
 * Copyright (C) 2016 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.model;

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.Sets.intersection;
import static com.google.common.graph.Graphs.inducedSubgraph;
import static com.google.common.graph.Graphs.reachableNodes;
import static com.google.common.graph.Graphs.transpose;
import static dagger.internal.codegen.DaggerStreams.instancesOf;
import static dagger.internal.codegen.DaggerStreams.toImmutableSet;
import static dagger.internal.codegen.DaggerStreams.toImmutableSetMultimap;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.ImmutableNetwork;
import com.google.common.graph.MutableNetwork;
import com.google.common.graph.Network;
import com.google.common.graph.NetworkBuilder;
import dagger.Module;
import java.util.Optional;
import java.util.stream.Stream;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * The immutable graph of bindings, dependency requests, and components for a valid root component.
 *
 * <h3>Nodes</h3>
 *
 * <p>There is a <b>{@link Binding}</b> for each owned binding in the graph. If a binding is owned
 * by more than one component, there is one binding object for that binding for every owning
 * component.
 *
 * <p>There is a <b>{@linkplain ComponentNode component node}</b> (without a binding) for each
 * component in the graph.
 *
 * <h3>Edges</h3>
 *
 * <p>There is a <b>{@linkplain DependencyEdge dependency edge}</b> for each dependency request in
 * the graph. Its target node is the binding for the binding that satisfies the request. For entry
 * point dependency requests, the source node is the component node for the component for which it
 * is an entry point. For other dependency requests, the source node is the binding for the binding
 * that contains the request.
 *
 * <p>There is a <b>subcomponent edge</b> for each parent-child component relationship in the graph.
 * The target node is the component node for the child component. For subcomponents defined by a
 * {@linkplain SubcomponentBuilderBindingEdge subcomponent builder binding} (either a method on the
 * component or a set of {@code @Module.subcomponents} annotation values), the source node is the
 * binding for the {@code @Subcomponent.Builder} type. For subcomponents defined by {@linkplain
 * ChildFactoryMethodEdge subcomponent factory methods}, the source node is the component node for
 * the parent.
 *
 * <p><b>Note that this API is experimental and will change.</b>
 */
public final class BindingGraph {
  private final ImmutableNetwork<Node, Edge> network;

  BindingGraph(Network<Node, Edge> network) {
    this.network = ImmutableNetwork.copyOf(network);
  }

  /** Returns the graph in its {@link Network} representation. */
  public ImmutableNetwork<Node, Edge> network() {
    return network;
  }

  @Override
  public int hashCode() {
    return network.hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if (other instanceof BindingGraph) {
      return network.equals(((BindingGraph) other).network);
    }
    return false;
  }

  @Override
  public String toString() {
    return network.toString();
  }

  /** Returns the bindings. */
  public ImmutableSet<Binding> bindings() {
    return nodes(Binding.class);
  }

  /** Returns the bindings for a key. */
  public ImmutableSet<Binding> bindings(Key key) {
    return nodeStream(Binding.class)
        .filter(binding -> binding.key().equals(key))
        .collect(toImmutableSet());
  }

  /** Returns the nodes that represent missing bindings. */
  public ImmutableSet<MissingBinding> missingBindings() {
    return nodes(MissingBinding.class);
  }

  /** Returns the component nodes. */
  public ImmutableSet<ComponentNode> componentNodes() {
    return nodes(ComponentNode.class);
  }

  /** Returns the component node for a component. */
  public Optional<ComponentNode> componentNode(ComponentPath component) {
    return nodeStream(ComponentNode.class)
        .filter(node -> node.componentPath().equals(component))
        .findFirst();
  }

  /** Returns the component nodes for a component. */
  public ImmutableSet<ComponentNode> componentNodes(TypeElement component) {
    return nodeStream(ComponentNode.class)
        .filter(node -> node.componentPath().currentComponent().equals(component))
        .collect(toImmutableSet());
  }

  /** Returns the component node for the root component. */
  public ComponentNode rootComponentNode() {
    return nodeStream(ComponentNode.class)
        .filter(node -> node.componentPath().atRoot())
        .findFirst()
        .get();
  }

  /** Returns the dependency edges. */
  public ImmutableSet<DependencyEdge> dependencyEdges() {
    return dependencyEdgeStream().collect(toImmutableSet());
  }

  /**
   * Returns the dependency edges for the dependencies of a binding. For valid graphs, each {@link
   * DependencyRequest} will map to a single {@link DependencyEdge}. When conflicting bindings exist
   * for a key, the multimap will have several edges for that {@link DependencyRequest}. Graphs that
   * have no binding for a key will have an edge whose {@linkplain EndpointPair#target() target
   * node} is a {@link MissingBinding}.
   */
  public ImmutableSetMultimap<DependencyRequest, DependencyEdge> dependencyEdges(Binding binding) {
    return dependencyEdgeStream(binding)
        .collect(toImmutableSetMultimap(DependencyEdge::dependencyRequest, edge -> edge));
  }

  /** Returns the dependency edges for a dependency request. */
  public ImmutableSet<DependencyEdge> dependencyEdges(DependencyRequest dependencyRequest) {
    return dependencyEdgeStream()
        .filter(edge -> edge.dependencyRequest().equals(dependencyRequest))
        .collect(toImmutableSet());
  }

  /**
   * Returns the dependency edges for the entry points of a given {@code component}. Each edge's
   * source node is that component's component node.
   */
  public ImmutableSet<DependencyEdge> entryPointEdges(ComponentPath component) {
    return dependencyEdgeStream(componentNode(component).get()).collect(toImmutableSet());
  }

  private Stream<DependencyEdge> dependencyEdgeStream(Node node) {
    return network.outEdges(node).stream().flatMap(instancesOf(DependencyEdge.class));
  }

  /**
   * Returns the dependency edges for all entry points for all components and subcomponents. Each
   * edge's source node is a component node.
   */
  public ImmutableSet<DependencyEdge> entryPointEdges() {
    return entryPointEdgeStream().collect(toImmutableSet());
  }

  /** Returns the binding or missing binding nodes that directly satisfy entry points. */
  public ImmutableSet<MaybeBinding> entryPointBindings() {
    return entryPointEdgeStream()
        .map(edge -> (MaybeBinding) network.incidentNodes(edge).target())
        .collect(toImmutableSet());
  }

  /**
   * Returns the edges for entry points that transitively depend on a binding or missing binding for
   * a key. Never returns an empty set.
   */
  public ImmutableSet<DependencyEdge> entryPointEdgesDependingOnBindingNode(MaybeBinding binding) {
    ImmutableNetwork<Node, DependencyEdge> dependencyGraph = dependencyGraph();
    Network<Node, DependencyEdge> subgraphDependingOnBindingNode =
        inducedSubgraph(
            dependencyGraph, reachableNodes(transpose(dependencyGraph).asGraph(), binding));
    ImmutableSet<DependencyEdge> entryPointEdges =
        intersection(entryPointEdges(), subgraphDependingOnBindingNode.edges()).immutableCopy();
    verify(!entryPointEdges.isEmpty(), "No entry points depend on binding %s", binding);
    return entryPointEdges;
  }

  // TODO(dpb): Make public. Cache.
  private ImmutableNetwork<Node, DependencyEdge> dependencyGraph() {
    MutableNetwork<Node, DependencyEdge> dependencyGraph =
        NetworkBuilder.from(network)
            .expectedNodeCount(network.nodes().size())
            .expectedEdgeCount((int) dependencyEdgeStream().count())
            .build();
    dependencyEdgeStream()
        .forEach(
            edge -> {
              EndpointPair<Node> endpoints = network.incidentNodes(edge);
              dependencyGraph.addEdge(endpoints.source(), endpoints.target(), edge);
            });
    return ImmutableNetwork.copyOf(dependencyGraph);
  }

  private <N extends Node> ImmutableSet<N> nodes(Class<N> clazz) {
    return nodeStream(clazz).collect(toImmutableSet());
  }

  private <N extends Node> Stream<N> nodeStream(Class<N> clazz) {
    return network.nodes().stream().flatMap(instancesOf(clazz));
  }

  private Stream<DependencyEdge> dependencyEdgeStream() {
    return network.edges().stream().flatMap(instancesOf(DependencyEdge.class));
  }

  private Stream<DependencyEdge> entryPointEdgeStream() {
    return dependencyEdgeStream().filter(DependencyEdge::isEntryPoint);
  }

  /**
   * An edge in the binding graph. Either a {@link DependencyEdge}, a {@link
   * ChildFactoryMethodEdge}, or a {@link SubcomponentBuilderBindingEdge}.
   */
  public interface Edge {}

  /**
   * An edge that represents a dependency on a binding.
   *
   * <p>Because one {@link DependencyRequest} may represent a dependency from two bindings (e.g., a
   * dependency of {@code Foo<String>} and {@code Foo<Number>} may have the same key and request
   * element), this class does not override {@link #equals(Object)} to use value semantics.
   *
   * <p>For entry points, the source node is the {@link ComponentNode} that contains the entry
   * point. Otherwise the source node is a {@link Binding}.
   *
   * <p>For dependencies on missing bindings, the target node is a {@link MissingBinding}. Otherwise
   * the target node is a {@link Binding}.
   */
  public interface DependencyEdge extends Edge {
    /** The dependency request. */
    DependencyRequest dependencyRequest();

    /** Returns {@code true} if this edge represents an entry point. */
    boolean isEntryPoint();
  }

  /**
   * An edge that represents a subcomponent factory method linking a parent component to a child
   * subcomponent.
   */
  public interface ChildFactoryMethodEdge extends Edge {
    /** The subcomponent factory method element. */
    ExecutableElement factoryMethod();
  }

  /**
   * An edge that represents the link between a parent component and a child subcomponent implied by
   * a subcomponent builder binding. The {@linkplain com.google.common.graph.EndpointPair#source()
   * source node} of this edge is a {@link Binding} for the subcomponent builder {@link Key} and the
   * {@linkplain com.google.common.graph.EndpointPair#target() target node} is a {@link
   * ComponentNode} for the child subcomponent.
   */
  public interface SubcomponentBuilderBindingEdge extends Edge {
    /**
     * The modules that {@linkplain Module#subcomponents() declare the subcomponent} that generated
     * this edge. Empty if the parent component has a subcomponent builder method and there are no
     * declaring modules.
     */
    ImmutableSet<TypeElement> declaringModules();
  }

  /** A node in the binding graph. Either a {@link Binding} or a {@link ComponentNode}. */
  // TODO(dpb): Make all the node/edge types top-level.
  public interface Node {
    /** The component this node belongs to. */
    ComponentPath componentPath();
  }

  /** A node in the binding graph that is either a {@link Binding} or a {@link MissingBinding}. */
  public interface MaybeBinding extends Node {

    /** The component that owns the binding, or in which the binding is missing. */
    @Override
    ComponentPath componentPath();

    /** The key of the binding, or for which there is no binding. */
    Key key();

    /** The binding, or empty if missing. */
    Optional<Binding> binding();
  }

  /** A node in the binding graph that represents a missing binding for a key in a component. */
  @AutoValue
  public abstract static class MissingBinding implements MaybeBinding {
    static MissingBinding create(ComponentPath component, Key key) {
      return new AutoValue_BindingGraph_MissingBinding(component, key);
    }

    /** The component in which the binding is missing. */
    @Override
    public abstract ComponentPath componentPath();

    /** The key for which there is no binding. */
    public abstract Key key();

    /** @deprecated This always returns {@code Optional.empty()}. */
    @Override
    @Deprecated
    public final Optional<Binding> binding() {
      return Optional.empty();
    }

    @Override
    public final String toString() {
      return String.format("missing binding for %s in %s", key(), componentPath());
    }
  }

  /**
   * A <b>component node</b> in the graph. Every entry point {@linkplain DependencyEdge dependency
   * edge}'s source node is a component node for the component containing the entry point.
   */
  public interface ComponentNode extends Node {

    /** The component represented by this node. */
    @Override
    ComponentPath componentPath();

    /** The entry points on this component. */
    ImmutableSet<DependencyRequest> entryPoints();

    /** The scopes declared on this component. */
    ImmutableSet<Scope> scopes();
  }
}
