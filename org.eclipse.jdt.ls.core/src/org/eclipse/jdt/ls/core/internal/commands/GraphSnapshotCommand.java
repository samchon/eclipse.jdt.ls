/*******************************************************************************
 * Copyright (c) 2026 Jeongho Nam and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Jeongho Nam - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.commands;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.stream.Stream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModel;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ModuleDeclaration;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeParameter;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.ls.core.internal.GraphSnapshotLock;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Exports one compiler-owned Java-model generation without per-declaration LSP
 * requests. The workspace root rule freezes resource state while the command
 * parses each primary-owner working copy once with binding resolution and walks
 * the Java model once.
 */
public final class GraphSnapshotCommand {

	public static final String COMMAND_ID = "java.graph.snapshot";
	public static final int SCHEMA_VERSION = 1;
	public static final int PROTOCOL_VERSION = 1;
	public static final String PRODUCER = "eclipse-jdtls-graph-snapshot";

	private static final String PLUGIN_ID = "org.eclipse.jdt.ls.core";
	private static final String SHA_256 = "SHA-256";
	private static String lastGeneration;
	private static String lastUniverse;
	private static long sequence;

	private GraphSnapshotCommand() {
	}

	/** Capture and publish only after the complete generation has been built. */
	public static synchronized Map<String, Object> execute(IProgressMonitor monitor) throws CoreException {
		Lock lock = GraphSnapshotLock.readLock();
		lock.lock();
		Map<String, Object> snapshot;
		try {
			IWorkspace workspace = ResourcesPlugin.getWorkspace();
			AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
			workspace.run(progress -> captured.set(capture(progress)), workspace.getRoot(), IWorkspace.AVOID_UPDATE, monitor);
			snapshot = captured.get();
		} finally {
			lock.unlock();
		}
		if (!Boolean.TRUE.equals(snapshot.get("complete"))) {
			snapshot.put("mode", "error");
			snapshot.put("sequence", sequence);
			return snapshot;
		}
		String universe = (String) snapshot.get("universe");
		String generation = (String) snapshot.get("generation");
		String mode;
		if (lastGeneration == null) {
			mode = "initial";
			sequence = 1;
		} else if (lastGeneration.equals(generation)) {
			mode = "unchanged";
		} else {
			mode = lastUniverse.equals(universe) ? "incremental" : "reload";
			sequence++;
		}
		snapshot.put("mode", mode);
		snapshot.put("sequence", sequence);
		lastUniverse = universe;
		lastGeneration = generation;
		return snapshot;
	}

	private static Map<String, Object> capture(IProgressMonitor monitor) throws CoreException {
		checkCanceled(monitor);
		IJavaModel model = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot());
		IJavaProject[] projectArray = model.getJavaProjects();
		Arrays.sort(projectArray, Comparator.comparing(IJavaElement::getElementName));
		Map<String, ICompilationUnit> workingCopies = residentWorkingCopies();
		List<Map<String, Object>> projects = new ArrayList<>();
		List<Map<String, Object>> sources = new ArrayList<>();
		List<Map<String, Object>> nodes = new ArrayList<>();
		List<Map<String, Object>> edges = new ArrayList<>();
		List<Map<String, Object>> diagnostics = new ArrayList<>();
		Set<String> emittedNodes = new java.util.HashSet<>();

		for (IJavaProject project : projectArray) {
			checkCanceled(monitor);
			if (!project.exists() || !project.getProject().isOpen()) {
				continue;
			}
			projects.add(projectMetadata(project, monitor));
			for (ICompilationUnit primary : sourceUnits(project, workingCopies)) {
				checkCanceled(monitor);
				ICompilationUnit resident = workingCopies.get(unitKey(primary));
				ICompilationUnit unit = resident == null ? primary.getWorkingCopy(monitor) : resident;
				try {
					org.eclipse.jdt.core.dom.CompilationUnit ast = semanticAst(unit, monitor);
					if (ast == null) {
						throw failure("JDT did not parse " + unit.getPath().toPortableString(), null);
					}
					captureUnit(project, unit, ast, sources, nodes, edges, diagnostics, emittedNodes, monitor);
				} finally {
					if (resident == null) {
						unit.discardWorkingCopy();
					}
				}
			}
		}

		sources.sort(mapComparator("uri"));
		nodes.sort(mapComparator("symbol", "uri"));
		edges.sort(mapComparator("from", "to", "kind"));
		diagnostics.sort(mapComparator("uri", "startLine", "startColumn", "code"));
		String universe = digestValue(projects);
		Map<String, Object> generationBody = map();
		generationBody.put("universe", universe);
		generationBody.put("sources", sources);
		generationBody.put("nodes", nodes);
		generationBody.put("edges", edges);
		generationBody.put("diagnostics", diagnostics);

		Map<String, Object> producer = map();
		producer.put("name", PRODUCER);
		producer.put("version", producerVersion());
		producer.put("compilerVersion", projectCompilerVersions(projects));

		Map<String, Object> capabilities = map();
		capabilities.put("atomicGenerations", true);
		capabilities.put("resident", true);
		capabilities.put("sourceDigests", true);
		capabilities.put("diskDigests", true);
		capabilities.put("unsavedBuffers", true);
		capabilities.put("diagnostics", true);
		capabilities.put("facts", List.of("contains"));
		Map<String, Object> coverage = map();
		coverage.put("contains", "complete");

		Map<String, Object> snapshot = map();
		snapshot.put("schemaVersion", SCHEMA_VERSION);
		snapshot.put("protocolVersion", PROTOCOL_VERSION);
		snapshot.put("producer", producer);
		snapshot.put("capabilities", capabilities);
		snapshot.put("universe", universe);
		snapshot.put("generation", digestValue(generationBody));
		snapshot.put("complete", diagnostics.stream().noneMatch(row -> "error".equals(row.get("severity"))));
		snapshot.put("coverage", coverage);
		snapshot.put("unresolved", List.of());
		snapshot.put("projects", projects);
		snapshot.put("sources", sources);
		snapshot.put("nodes", nodes);
		snapshot.put("edges", edges);
		snapshot.put("diagnostics", diagnostics);
		return snapshot;
	}

	private static Map<String, ICompilationUnit> residentWorkingCopies() {
		Map<String, ICompilationUnit> answer = new TreeMap<>();
		ICompilationUnit[] copies = JavaCore.getWorkingCopies(null);
		if (copies != null) {
			for (ICompilationUnit copy : copies) {
				if (copy != null && copy.exists()) {
					answer.put(unitKey(copy), copy);
				}
			}
		}
		return answer;
	}

	private static List<ICompilationUnit> sourceUnits(IJavaProject project, Map<String, ICompilationUnit> workingCopies) throws JavaModelException {
		Map<String, ICompilationUnit> units = new TreeMap<>();
		for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
			if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
				continue;
			}
			for (IJavaElement child : root.getChildren()) {
				if (child instanceof IPackageFragment fragment) {
					for (ICompilationUnit unit : fragment.getCompilationUnits()) {
						units.put(unitKey(unit), unit);
					}
				}
			}
		}
		for (ICompilationUnit copy : workingCopies.values()) {
			if (project.equals(copy.getJavaProject())) {
				units.put(unitKey(copy), copy);
			}
		}
		return new ArrayList<>(units.values());
	}

	private static org.eclipse.jdt.core.dom.CompilationUnit semanticAst(
			ICompilationUnit unit, IProgressMonitor monitor) {
		ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
		parser.setSource(unit);
		parser.setResolveBindings(true);
		parser.setBindingsRecovery(true);
		parser.setStatementsRecovery(true);
		parser.setForceProblemDetection(true);
		return (org.eclipse.jdt.core.dom.CompilationUnit) parser.createAST(monitor);
	}

	private static void captureUnit(IJavaProject project, ICompilationUnit unit, org.eclipse.jdt.core.dom.CompilationUnit ast,
			List<Map<String, Object>> sources, List<Map<String, Object>> nodes, List<Map<String, Object>> edges,
			List<Map<String, Object>> diagnostics, Set<String> emitted, IProgressMonitor monitor) throws CoreException {
		unit.open(monitor);
		IBuffer buffer = unit.getBuffer();
		if (buffer == null) {
			return;
		}
		String content = buffer.getContents();
		IResource resource = unit.getResource();
		String uri = sourceUri(unit, resource);
		SourceText source = new SourceText(content);

		Map<String, Object> sourceRow = map();
		sourceRow.put("project", project.getElementName());
		sourceRow.put("uri", uri);
		sourceRow.put("checkerDigest", digestText(content));
		sourceRow.put("checkerEncoding", "jdt-utf16-code-units-v1");
		sourceRow.put("diskDigest", diskDigest(resource));
		sources.add(sourceRow);

		captureAstDeclarations(project, ast, source, uri, emitted, nodes, edges, diagnostics);
		for (IProblem problem : ast.getProblems()) {
			Map<String, Object> row = map();
			row.put("uri", uri);
			row.put("severity", problem.isError() ? "error" : problem.isWarning() ? "warning" : "information");
			row.put("code", Integer.toString(problem.getID()));
			row.put("message", problem.getMessage());
			int start = Math.max(0, problem.getSourceStart());
			int end = Math.max(start, problem.getSourceEnd() + 1);
			row.put("evidence", source.evidence(uri, start, end));
			diagnostics.add(row);
		}
	}

	private static void captureAstDeclarations(IJavaProject project, org.eclipse.jdt.core.dom.CompilationUnit ast,
			SourceText source, String uri, Set<String> emitted, List<Map<String, Object>> nodes,
			List<Map<String, Object>> edges, List<Map<String, Object>> diagnostics) {
		String fileSymbol = "java/" + project.getElementName() + "/file/" + digest(uri.getBytes(StandardCharsets.UTF_8));
		Map<String, Object> fileNode = astNode(project, fileSymbol, fileSymbol, "persistent", uri, fileName(uri), uri,
				"file", "", "file", 0, source.evidence(uri, 0, source.length));
		emitted.add(fileSymbol);
		nodes.add(fileNode);
		Map<String, String> displays = new TreeMap<>();
		displays.put(fileSymbol, uri);

		ast.accept(new ASTVisitor() {
			private final Map<ASTNode, String> owners = new java.util.IdentityHashMap<>();
			private final Map<String, Integer> occurrences = new TreeMap<>();
			private String topOwner = fileSymbol;

			@Override
			public void preVisit(ASTNode node) {
				if (node instanceof PackageDeclaration declaration) {
					String name = declaration.getName().getFullyQualifiedName();
					String symbol = "java/" + project.getElementName() + "/package/" + name;
					add(node, symbol, declaration.resolveBinding(), "persistent", name, name, "package", "", "package", declaration, topOwner);
					topOwner = symbol;
				} else if (node instanceof ModuleDeclaration declaration) {
					String name = declaration.getName().getFullyQualifiedName();
					String symbol = "java/" + project.getElementName() + "/module/" + name;
					add(node, symbol, declaration.resolveBinding(), "persistent", name, name, "module", "", "module", declaration, fileSymbol);
					topOwner = symbol;
				} else if (node instanceof AbstractTypeDeclaration declaration) {
					ITypeBinding binding = declaration.resolveBinding();
					String name = declaration.getName().getIdentifier();
					boolean local = binding != null && binding.isLocal();
					String binary = binding == null ? "" : nullToEmpty(binding.getBinaryName());
					String owner = ownerOf(node);
					String symbol = !local && !binary.isEmpty()
							? "java/" + project.getElementName() + "/type/" + binary
							: unique(owner + "/local-type/" + name);
					String qualified = binding == null ? name : nullToEmpty(binding.getQualifiedName());
					String declarationKind = declaration instanceof AnnotationTypeDeclaration ? "annotation"
							: declaration instanceof RecordDeclaration ? "record"
							: declaration instanceof EnumDeclaration ? "enum" : "type";
					String kind = declaration instanceof AnnotationTypeDeclaration ? "interface"
							: declaration instanceof EnumDeclaration ? "enum"
							: declaration instanceof TypeDeclaration typed && typed.isInterface() ? "interface" : "class";
					add(node, symbol, binding, local ? "structural" : "persistent", name,
							qualified.isEmpty() ? displayOwner(node) + "." + name : qualified, kind, "", declarationKind, declaration, owner);
				} else if (node instanceof AnonymousClassDeclaration declaration) {
					ITypeBinding binding = declaration.resolveBinding();
					String owner = ownerOf(node);
					String base = owner + "/anonymous/" + anonymousBase(binding);
					String symbol = unique(base);
					add(node, symbol, binding, "structural", "<anonymous>", displayOwner(node) + ".<anonymous>",
							"class", "", "anonymous-class", declaration, owner);
				} else if (node instanceof MethodDeclaration declaration) {
					IMethodBinding binding = declaration.resolveBinding();
					String owner = ownerOf(node);
					boolean constructor = declaration.isConstructor();
					String name = constructor ? ownerSimpleName(node) : declaration.getName().getIdentifier();
					String signature = methodSignature(binding, declaration);
					String parameters = parameterSignature(binding, declaration);
					String symbol = owner + "/" + (constructor ? "constructor" : "method") + "/" + name + parameters;
					String declarationKind = declaration.getParent() instanceof AnnotationTypeDeclaration ? "annotation-element" : constructor ? "constructor" : "method";
					add(node, symbol, binding, "persistent", name, displayOwner(node) + "." + name,
							constructor ? "constructor" : "method", signature, declarationKind, declaration, owner);
				} else if (node instanceof AnnotationTypeMemberDeclaration declaration) {
					IMethodBinding binding = declaration.resolveBinding();
					String owner = ownerOf(node);
					String name = declaration.getName().getIdentifier();
					String signature = binding == null ? "():" + declaration.getType() : "():" + canonicalType(binding.getReturnType());
					String symbol = owner + "/annotation-element/" + name + "()";
					add(node, symbol, binding, "persistent", name, displayOwner(node) + "." + name,
							"method", signature, "annotation-element", declaration, owner);
				} else if (node instanceof EnumConstantDeclaration declaration) {
					IVariableBinding binding = declaration.resolveVariable();
					String owner = ownerOf(node);
					String name = declaration.getName().getIdentifier();
					String symbol = owner + "/enum-constant/" + name;
					add(node, symbol, binding, "persistent", name, displayOwner(node) + "." + name,
							"field", binding == null ? "" : canonicalType(binding.getType()), "enum-constant", declaration, owner);
				} else if (node instanceof VariableDeclaration declaration) {
					IVariableBinding binding = declaration.resolveBinding();
					String owner = ownerOf(node);
					String name = declaration.getName().getIdentifier();
					String declarationKind = variableKind(declaration, binding);
					String kind = "parameter".equals(declarationKind) ? "parameter"
							: Set.of("field", "record-component").contains(declarationKind) ? "field" : "variable";
					String type = binding == null ? "" : canonicalType(binding.getType());
					String base = owner + "/" + declarationKind + "/" + name + (type.isEmpty() ? "" : ":" + type);
					boolean stableMember = binding != null && binding.isField();
					String symbol = stableMember ? base : unique(base);
					add(node, symbol, binding, stableMember ? "persistent" : "structural", name,
							displayOwner(node) + "." + name, kind, type, declarationKind, declaration, owner);
				} else if (node instanceof TypeParameter parameter) {
					ITypeBinding binding = parameter.resolveBinding();
					String owner = ownerOf(node);
					String name = parameter.getName().getIdentifier();
					String symbol = owner + "/type-parameter/" + name;
					add(node, symbol, binding, "persistent", name, displayOwner(node) + "." + name,
							"type", parameter.typeBounds().toString(), "type-parameter", parameter, owner);
				} else if (node instanceof LambdaExpression lambda) {
					IMethodBinding binding = lambda.resolveMethodBinding();
					String owner = ownerOf(node);
					List<String> parameterTexts = new ArrayList<>();
					for (Object parameter : lambda.parameters()) {
						parameterTexts.add(String.valueOf(parameter));
					}
					String header = String.join(",", parameterTexts);
					String symbol = unique(owner + "/lambda/(" + header + ")");
					add(node, symbol, binding, "structural", "<lambda>", displayOwner(node) + ".<lambda>",
							"function", methodSignature(binding, null), "lambda", lambda, owner);
				}
			}

			private void add(ASTNode ownerNode, String symbol, IBinding binding, String stability, String name,
					String qualifiedName, String kind, String signature, String declarationKind, ASTNode evidenceNode, String owner) {
				owners.put(ownerNode, symbol);
				displays.put(symbol, qualifiedName);
				if (binding == null) {
					Map<String, Object> diagnostic = map();
					diagnostic.put("uri", uri);
					diagnostic.put("severity", "error");
					diagnostic.put("code", "jdt-unresolved-declaration-binding");
					diagnostic.put("message", "JDT could not resolve the " + declarationKind + " binding for " + name);
					diagnostic.put("evidence", source.evidence(uri, evidenceNode.getStartPosition(),
							evidenceNode.getStartPosition() + evidenceNode.getLength()));
					diagnostics.add(diagnostic);
				}
				if (!emitted.add(symbol)) return;
				String nativeKey = binding == null ? symbol : nullToEmpty(binding.getKey());
				Map<String, Object> value = astNode(project, symbol, nativeKey.isEmpty() ? symbol : nativeKey, stability,
						uri, name, qualifiedName, kind, signature, declarationKind, binding == null ? 0 : binding.getModifiers(),
						source.evidence(uri, evidenceNode.getStartPosition(), evidenceNode.getStartPosition() + evidenceNode.getLength()));
				nodes.add(value);
				edges.add(contains(owner, symbol, value));
			}

			private String ownerOf(ASTNode node) {
				for (ASTNode cursor = node.getParent(); cursor != null; cursor = cursor.getParent()) {
					String owner = owners.get(cursor);
					if (owner != null) return owner;
				}
				return topOwner;
			}

			private String displayOwner(ASTNode node) {
				String owner = ownerOf(node);
				return displays.getOrDefault(owner, owner);
			}

			private String ownerSimpleName(ASTNode node) {
				String display = displayOwner(node);
				int dot = display.lastIndexOf('.');
				return dot < 0 ? display : display.substring(dot + 1);
			}

			private String unique(String base) {
				int next = occurrences.merge(base, 1, Integer::sum);
				return next == 1 ? base : base + "/duplicate-" + next;
			}
		});
	}

	private static Map<String, Object> astNode(IJavaProject project, String symbol, String nativeKey, String stability,
			String uri, String name, String qualifiedName, String kind, String signature, String declarationKind,
			int flags, Map<String, Object> evidence) {
		Map<String, Object> answer = map();
		answer.put("project", project.getElementName());
		answer.put("symbol", symbol);
		answer.put("nativeKey", nativeKey);
		answer.put("stability", stability);
		answer.put("uri", uri);
		answer.put("name", name);
		answer.put("qualifiedName", qualifiedName);
		answer.put("kind", kind);
		answer.put("signature", signature);
		answer.put("declarationKind", declarationKind);
		answer.put("exported", org.eclipse.jdt.core.dom.Modifier.isPublic(flags) || org.eclipse.jdt.core.dom.Modifier.isProtected(flags));
		answer.put("modifiers", modifiers(flags));
		answer.put("evidence", evidence);
		return answer;
	}

	private static String fileName(String uri) {
		try {
			Path path = Path.of(URI.create(uri));
			return path.getFileName() == null ? uri : path.getFileName().toString();
		} catch (IllegalArgumentException exception) {
			int slash = Math.max(uri.lastIndexOf('/'), uri.lastIndexOf('\\'));
			return slash < 0 ? uri : uri.substring(slash + 1);
		}
	}

	private static String anonymousBase(ITypeBinding binding) {
		if (binding == null) return "unresolved";
		ITypeBinding superclass = binding.getSuperclass();
		if (superclass != null && !"java.lang.Object".equals(superclass.getQualifiedName())) {
			return canonicalType(superclass);
		}
		ITypeBinding[] interfaces = binding.getInterfaces();
		return interfaces.length == 0 ? "java.lang.Object" : canonicalType(interfaces[0]);
	}

	private static String methodSignature(IMethodBinding binding, MethodDeclaration declaration) {
		String parameters = parameterSignature(binding, declaration);
		if (binding != null) {
			return parameters + (binding.isConstructor() ? "" : ":" + canonicalType(binding.getReturnType()));
		}
		if (declaration == null || declaration.isConstructor()) return parameters;
		return parameters + ":" + declaration.getReturnType2();
	}

	private static String parameterSignature(IMethodBinding binding, MethodDeclaration declaration) {
		if (binding != null) {
			return "(" + Arrays.stream(binding.getParameterTypes()).map(GraphSnapshotCommand::canonicalType)
					.reduce((left, right) -> left + "," + right).orElse("") + ")";
		}
		if (declaration == null) return "()";
		List<String> parameters = new ArrayList<>();
		for (Object parameter : declaration.parameters()) {
			parameters.add(String.valueOf(parameter));
		}
		return "(" + String.join(",", parameters) + ")";
	}

	private static String canonicalType(ITypeBinding binding) {
		if (binding == null) return "";
		if (binding.isArray()) return canonicalType(binding.getElementType()) + "[]".repeat(binding.getDimensions());
		ITypeBinding declaration = binding.getErasure();
		String qualified = nullToEmpty(declaration.getQualifiedName());
		return qualified.isEmpty() ? declaration.getName() : qualified;
	}

	private static String variableKind(VariableDeclaration declaration, IVariableBinding binding) {
		ASTNode parent = declaration.getParent();
		if (parent instanceof RecordDeclaration record && record.recordComponents().contains(declaration)) {
			return "record-component";
		}
		if (binding != null && binding.isEnumConstant()) return "enum-constant";
		if (binding != null && binding.isField()) return "field";
		if (declaration instanceof SingleVariableDeclaration &&
				(parent instanceof MethodDeclaration || parent instanceof LambdaExpression)) {
			return "parameter";
		}
		return "variable";
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private static Map<String, Object> contains(String from, String to, Map<String, Object> target) {
		Map<String, Object> edge = map();
		edge.put("from", from);
		edge.put("to", to);
		edge.put("kind", "contains");
		edge.put("evidence", target.get("evidence"));
		return edge;
	}

	private static List<String> modifiers(int flags) {
		List<String> answer = new ArrayList<>();
		if (Flags.isPublic(flags)) answer.add("public");
		if (Flags.isProtected(flags)) answer.add("protected");
		if (Flags.isPrivate(flags)) answer.add("private");
		if (Flags.isStatic(flags)) answer.add("static");
		if (Flags.isAbstract(flags)) answer.add("abstract");
		if (Flags.isFinal(flags)) answer.add("final");
		return answer;
	}

	private static Map<String, Object> projectMetadata(IJavaProject project, IProgressMonitor monitor) throws CoreException {
		Map<String, Object> metadata = map();
		metadata.put("name", project.getElementName());
		metadata.put("location", uriString(project.getProject().getLocationURI()));
		metadata.put("output", project.getOutputLocation().toPortableString());
		Map<String, String> options = new TreeMap<>(project.getOptions(true));
		metadata.put("options", options);
		metadata.put("compilerVersion", options.getOrDefault(JavaCore.COMPILER_COMPLIANCE, "unknown"));

		List<Map<String, Object>> classpath = new ArrayList<>();
		for (IClasspathEntry entry : project.getResolvedClasspath(true)) {
			checkCanceled(monitor);
			Map<String, Object> row = map();
			row.put("path", entry.getPath().toPortableString());
			row.put("entryKind", entry.getEntryKind());
			row.put("contentKind", entry.getContentKind());
			row.put("exported", entry.isExported());
			row.put("combineAccessRules", entry.combineAccessRules());
			row.put("output", entry.getOutputLocation() == null ? "" : entry.getOutputLocation().toPortableString());
			row.put("contentDigest", classpathDigest(entry, monitor));
			row.put(
					"accessRules",
					Arrays.stream(entry.getAccessRules())
							.map(rule -> rule.getKind() + ":" + rule.getPattern().toPortableString() + ":" + rule.ignoreIfBetter())
							.toList());
			List<String> attributes = Arrays.stream(entry.getExtraAttributes())
					.sorted(Comparator.comparing(IClasspathAttribute::getName).thenComparing(IClasspathAttribute::getValue))
					.map(attribute -> attribute.getName() + "=" + attribute.getValue()).toList();
			row.put("attributes", attributes);
			row.put("inclusions", Arrays.stream(entry.getInclusionPatterns()).map(value -> value.toPortableString()).sorted().toList());
			row.put("exclusions", Arrays.stream(entry.getExclusionPatterns()).map(value -> value.toPortableString()).sorted().toList());
			classpath.add(row);
		}
		metadata.put("classpath", classpath);
		return metadata;
	}

	private static String classpathDigest(IClasspathEntry entry, IProgressMonitor monitor) throws CoreException {
		org.eclipse.core.runtime.IPath entryPath = entry.getPath();
		if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
			return digest(("source-root:" + entryPath.toPortableString()).getBytes(StandardCharsets.UTF_8));
		}
		if (entry.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
			IResource dependency = ResourcesPlugin.getWorkspace().getRoot().findMember(entryPath);
			if (dependency != null) {
				IJavaProject javaProject = JavaCore.create(dependency.getProject());
				if (javaProject.exists()) {
					return pathDigest(javaProject.getOutputLocation(), monitor);
				}
			}
			return digest(("missing-project:" + entryPath.toPortableString()).getBytes(StandardCharsets.UTF_8));
		}
		return pathDigest(entryPath, monitor);
	}

	private static String pathDigest(org.eclipse.core.runtime.IPath entryPath, IProgressMonitor monitor) throws CoreException {
		IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(entryPath);
		Path path = resource != null && resource.getLocation() != null ? resource.getLocation().toFile().toPath() : entryPath.toFile().toPath();
		if (!Files.exists(path)) {
			return digest(("missing:" + entryPath.toPortableString()).getBytes(StandardCharsets.UTF_8));
		}
		try {
			if (Files.isRegularFile(path)) return digest(path, monitor);
			MessageDigest hash = newDigest();
			try (Stream<Path> walk = Files.walk(path)) {
				List<Path> files = walk.filter(Files::isRegularFile).sorted(Comparator.comparing(value -> path.relativize(value).toString())).toList();
				for (Path file : files) {
					checkCanceled(monitor);
					update(hash, path.relativize(file).toString().replace('\\', '/'));
					update(hash, digest(file, monitor));
				}
			}
			return HexFormat.of().formatHex(hash.digest());
		} catch (IOException exception) {
			throw failure("Unable to digest classpath entry " + path, exception);
		}
	}

	private static String diskDigest(IResource resource) throws CoreException {
		if (!(resource instanceof IFile file) || file.getLocation() == null || !file.exists()) return "";
		try {
			return digest(file.getLocation().toFile().toPath(), null);
		} catch (IOException exception) {
			throw failure("Unable to digest source " + file.getFullPath(), exception);
		}
	}

	private static String digest(Path path, IProgressMonitor monitor) throws IOException {
		MessageDigest hash = newDigest();
		byte[] buffer = new byte[64 * 1024];
		try (InputStream input = Files.newInputStream(path)) {
			int read;
			while ((read = input.read(buffer)) != -1) {
				if (monitor != null) checkCanceled(monitor);
				hash.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(hash.digest());
	}

	private static String sourceUri(ICompilationUnit unit, IResource resource) {
		URI location = resource == null ? null : resource.getLocationURI();
		return location == null ? unit.getPath().toFile().toURI().toString() : location.toString();
	}

	private static String unitKey(ICompilationUnit unit) {
		return unit.getPrimary().getPath().toPortableString();
	}

	private static String projectCompilerVersions(List<Map<String, Object>> projects) {
		return projects.stream().map(project -> (String) project.get("compilerVersion")).distinct().sorted().reduce((left, right) -> left + "; " + right).orElse("unknown");
	}

	private static String producerVersion() {
		Bundle bundle = FrameworkUtil.getBundle(GraphSnapshotCommand.class);
		return bundle == null ? "unknown" : bundle.getVersion().toString();
	}

	private static Comparator<Map<String, Object>> mapComparator(String... keys) {
		return (left, right) -> {
			for (String key : keys) {
				int compared = String.valueOf(left.get(key)).compareTo(String.valueOf(right.get(key)));
				if (compared != 0) return compared;
			}
			return 0;
		};
	}

	private static String digestValue(Object value) {
		MessageDigest hash = newDigest();
		hashValue(hash, value);
		return HexFormat.of().formatHex(hash.digest());
	}

	private static void hashValue(MessageDigest hash, Object value) {
		if (value == null) {
			update(hash, "null");
		} else if (value instanceof Map<?, ?> values) {
			update(hash, "map");
			values.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey()))).forEach(entry -> {
				update(hash, String.valueOf(entry.getKey()));
				hashValue(hash, entry.getValue());
			});
		} else if (value instanceof Collection<?> values) {
			update(hash, "list");
			values.forEach(item -> hashValue(hash, item));
		} else {
			update(hash, value.getClass().getName());
			update(hash, String.valueOf(value));
		}
	}

	private static void update(MessageDigest hash, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		hash.update(new byte[] { (byte) (bytes.length >>> 24), (byte) (bytes.length >>> 16), (byte) (bytes.length >>> 8), (byte) bytes.length });
		hash.update(bytes);
	}

	private static String digest(byte[] bytes) {
		return HexFormat.of().formatHex(newDigest().digest(bytes));
	}

	/** Hashes the exact UTF-16 code units JDT reconciled, without replacement. */
	private static String digestText(String text) {
		MessageDigest hash = newDigest();
		update(hash, "jdt-utf16-code-units-v1");
		for (int index = 0; index < text.length(); index++) {
			char value = text.charAt(index);
			hash.update((byte) (value >>> 8));
			hash.update((byte) value);
		}
		return HexFormat.of().formatHex(hash.digest());
	}

	private static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance(SHA_256);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(SHA_256 + " is unavailable", exception);
		}
	}

	private static CoreException failure(String message, Exception cause) {
		return new CoreException(new Status(IStatus.ERROR, PLUGIN_ID, message, cause));
	}

	private static String uriString(URI uri) {
		return uri == null ? "" : uri.toString();
	}

	private static void checkCanceled(IProgressMonitor monitor) {
		if (monitor != null && monitor.isCanceled()) throw new OperationCanceledException();
	}

	private static Map<String, Object> map() {
		return new LinkedHashMap<>();
	}

	private static final class SourceText {
		private final int[] starts;
		private final int length;

		SourceText(String content) {
			this.length = content.length();
			List<Integer> lines = new ArrayList<>();
			lines.add(0);
			for (int index = 0; index < content.length(); index++) {
				if (content.charAt(index) == '\n') lines.add(index + 1);
			}
			this.starts = lines.stream().mapToInt(Integer::intValue).toArray();
		}

		Map<String, Object> evidence(String uri, int rawStart, int rawEnd) {
			int start = Math.min(length, Math.max(0, rawStart));
			int end = Math.min(length, Math.max(start, rawEnd));
			int startLine = line(start);
			int endLine = line(end);
			Map<String, Object> answer = map();
			answer.put("uri", uri);
			answer.put("startLine", startLine + 1);
			answer.put("startColumn", start - starts[startLine] + 1);
			answer.put("endLine", endLine + 1);
			answer.put("endColumn", end - starts[endLine] + 1);
			return answer;
		}

		private int line(int offset) {
			int found = Arrays.binarySearch(starts, offset);
			return found >= 0 ? found : Math.max(0, -found - 2);
		}
	}
}
