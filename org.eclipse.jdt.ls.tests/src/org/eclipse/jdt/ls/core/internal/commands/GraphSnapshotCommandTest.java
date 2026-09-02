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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IAccessRule;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ls.core.internal.GraphSnapshotLock;
import org.eclipse.jdt.ls.core.internal.WorkspaceHelper;
import org.eclipse.jdt.ls.core.internal.handlers.WorkspaceExecuteCommandHandler;
import org.eclipse.jdt.ls.core.internal.managers.AbstractProjectsManagerBasedTest;
import org.junit.jupiter.api.Test;

public class GraphSnapshotCommandTest extends AbstractProjectsManagerBasedTest {

	@Test
	public void exportsFrozenWorkspaceGenerationIncludingResidentChanges() throws Exception {
		importProjects("maven/salut2");
		IProject project = WorkspaceHelper.getProject("salut2");
		IJavaProject javaProject = JavaCore.create(project);
		javaProject.setOption(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_21);
		javaProject.setOption(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_21);
		assertTrue(WorkspaceExecuteCommandHandler.getInstance().getAllCommands().contains(GraphSnapshotCommand.COMMAND_ID));
		Map<String, Object> first = GraphSnapshotCommand.execute(monitor);
		assertEquals(GraphSnapshotCommand.SCHEMA_VERSION, first.get("schemaVersion"));
		assertEquals(GraphSnapshotCommand.PROTOCOL_VERSION, first.get("protocolVersion"));
		assertFalse(rows(first, "sources").isEmpty());
		assertTrue(rows(first, "nodes").stream().anyMatch(node -> "class".equals(node.get("kind"))));
		assertTrue(rows(first, "edges").stream().allMatch(edge -> "contains".equals(edge.get("kind"))));

		Map<String, Object> unchanged = GraphSnapshotCommand.execute(monitor);
		assertEquals(first.get("generation"), unchanged.get("generation"));
		assertEquals("unchanged", unchanged.get("mode"));
		assertEquals(first.get("sequence"), unchanged.get("sequence"));

		IFile file = project.getFile("src/main/java/foo/Bar.java");
		file.setCharset(StandardCharsets.US_ASCII.name(), monitor);
		ICompilationUnit unit = JavaCore.createCompilationUnitFrom(file);
		unit.becomeWorkingCopy(monitor);
		try {
			String disk = unit.getBuffer().getContents();
			String resident = disk + "\nclass ResidentOnly { void run() { class Local {} Runnable r = () -> {}; Object a = new Object() {}; } }\n"
					+ "enum ResidentEnum { VALUE }\n@interface ResidentNote { String value(); }\nrecord ResidentPoint(int x) {}\n";
			unit.getBuffer().setContents(resident);
			unit.reconcile(ICompilationUnit.NO_AST, true, null, monitor);
			Map<String, Object> changed = GraphSnapshotCommand.execute(monitor);
			assertEquals("incremental", changed.get("mode"));
			assertEquals(true, changed.get("complete"));
			assertNotEquals(first.get("generation"), changed.get("generation"));
			assertTrue(rows(changed, "nodes").stream().anyMatch(node -> "ResidentOnly".equals(node.get("name"))));
			assertTrue(rows(changed, "nodes").stream().anyMatch(node -> "Local".equals(node.get("name")) && "structural".equals(node.get("stability"))));
			assertTrue(rows(changed, "nodes").stream().anyMatch(node -> "lambda".equals(node.get("declarationKind"))));
			assertTrue(rows(changed, "nodes").stream().anyMatch(node -> "anonymous-class".equals(node.get("declarationKind"))));
			assertTrue(rows(changed, "nodes").stream().anyMatch(node -> "enum-constant".equals(node.get("declarationKind"))));
			assertTrue(rows(changed, "nodes").stream().anyMatch(node -> "annotation-element".equals(node.get("declarationKind"))));
			assertTrue(rows(changed, "nodes").stream().anyMatch(node -> "record-component".equals(node.get("declarationKind"))));
			Map<String, Object> source = rows(changed, "sources").stream()
					.filter(row -> String.valueOf(row.get("uri")).endsWith("/foo/Bar.java"))
					.findFirst().orElseThrow();
			assertNotEquals(source.get("checkerDigest"), source.get("diskDigest"));

			long sequence = ((Number) changed.get("sequence")).longValue();
			unit.getBuffer().setContents(disk + "\nclass Broken {\n");
			Map<String, Object> broken = GraphSnapshotCommand.execute(monitor);
			assertEquals(false, broken.get("complete"));
			assertEquals("error", broken.get("mode"));
			assertEquals(sequence, ((Number) broken.get("sequence")).longValue());
			assertTrue(rows(broken, "diagnostics").stream().anyMatch(row -> "error".equals(row.get("severity"))));

			unit.getBuffer().setContents(resident + "// \u00e9\n");
			Map<String, Object> unicode = GraphSnapshotCommand.execute(monitor);
			unit.getBuffer().setContents(resident + "// ?\n");
			Map<String, Object> question = GraphSnapshotCommand.execute(monitor);
			assertNotEquals(sourceDigest(unicode, file), sourceDigest(question, file));
		} finally {
			unit.discardWorkingCopy();
		}
	}

	@Test
	public void documentMutationLockExcludesBulkCapture() throws Exception {
		java.util.concurrent.locks.Lock read = GraphSnapshotLock.readLock();
		java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch acquired = new java.util.concurrent.CountDownLatch(1);
		read.lock();
		Thread writer = new Thread(() -> {
			started.countDown();
			java.util.concurrent.locks.Lock write = GraphSnapshotLock.writeLock();
			write.lock();
			try {
				acquired.countDown();
			} finally {
				write.unlock();
			}
		});
		writer.start();
		try {
			assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
			assertFalse(acquired.await(100, java.util.concurrent.TimeUnit.MILLISECONDS));
		} finally {
			read.unlock();
		}
		assertTrue(acquired.await(5, java.util.concurrent.TimeUnit.SECONDS));
		writer.join(5_000);
	}

	@Test
	public void savedSourceEditMovesGenerationButNotBuildUniverse() throws Exception {
		importProjects("maven/salut2");
		IFile file = WorkspaceHelper.getProject("salut2").getFile("src/main/java/foo/Bar.java");
		String original;
		try (var input = file.getContents()) {
			original = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		Map<String, Object> first = GraphSnapshotCommand.execute(monitor);
		try {
			String edited = original + "\nclass SavedBodyEdit {}\n";
			file.setContents(new ByteArrayInputStream(edited.getBytes(StandardCharsets.UTF_8)), true, false, monitor);
			Map<String, Object> changed = GraphSnapshotCommand.execute(monitor);
			assertEquals(first.get("universe"), changed.get("universe"));
			assertNotEquals(first.get("generation"), changed.get("generation"));
			assertEquals("incremental", changed.get("mode"));
		} finally {
			file.setContents(new ByteArrayInputStream(original.getBytes(StandardCharsets.UTF_8)), true, false, monitor);
		}
	}

	@Test
	public void exportsModuleDeclarations() throws Exception {
		importProjects("maven/modular-project");
		Map<String, Object> snapshot = GraphSnapshotCommand.execute(monitor);
		assertTrue(rows(snapshot, "nodes").stream().anyMatch(node -> "module".equals(node.get("kind"))));
	}

	@Test
	public void classpathAccessRulesMoveTheBuildUniverse() throws Exception {
		importProjects("maven/salut2");
		IProject project = WorkspaceHelper.getProject("salut2");
		IJavaProject javaProject = JavaCore.create(project);
		java.nio.file.Path library = project.getLocation().append("lib/access-rules.jar").toFile().toPath();
		java.nio.file.Files.createDirectories(library.getParent());
		try (JarOutputStream ignored = new JarOutputStream(java.nio.file.Files.newOutputStream(library))) {
			// An empty but valid archive is enough: only its access rules move below.
		}
		IClasspathEntry[] original = javaProject.getRawClasspath();
		IClasspathEntry[] withLibrary = Arrays.copyOf(original, original.length + 1);
		withLibrary[original.length] =
				libraryEntry(library, "internal/**", IAccessRule.K_NON_ACCESSIBLE, false);
		javaProject.setRawClasspath(withLibrary, monitor);
		Map<String, Object> baseline = GraphSnapshotCommand.execute(monitor);
		withLibrary[original.length] =
				libraryEntry(library, "public/**", IAccessRule.K_NON_ACCESSIBLE, false);
		javaProject.setRawClasspath(withLibrary, monitor);
		Map<String, Object> patternChanged = GraphSnapshotCommand.execute(monitor);
		withLibrary[original.length] =
				libraryEntry(library, "public/**", IAccessRule.K_DISCOURAGED, false);
		javaProject.setRawClasspath(withLibrary, monitor);
		Map<String, Object> kindChanged = GraphSnapshotCommand.execute(monitor);
		withLibrary[original.length] =
				libraryEntry(library, "public/**", IAccessRule.K_DISCOURAGED, true);
		javaProject.setRawClasspath(withLibrary, monitor);
		Map<String, Object> ignoreChanged = GraphSnapshotCommand.execute(monitor);
		assertNotEquals(baseline.get("universe"), patternChanged.get("universe"));
		assertNotEquals(patternChanged.get("universe"), kindChanged.get("universe"));
		assertNotEquals(kindChanged.get("universe"), ignoreChanged.get("universe"));
		assertTrue(
				rows(ignoreChanged, "projects").stream()
						.flatMap(row -> rows(row, "classpath").stream())
						.filter(row -> String.valueOf(row.get("path")).endsWith("access-rules.jar"))
						.anyMatch(
								row -> rowsOfStrings(row, "accessRules").stream()
										.anyMatch(
												value ->
														value.contains("public/**")
																&& value.endsWith(":true"))));
	}

	@Test
	public void projectClasspathCombinationMovesTheBuildUniverse() throws Exception {
		importProjects("maven/multimodule");
		IJavaProject javaProject =
				Arrays.stream(JavaCore.create(WorkspaceHelper.getWorkspaceRoot()).getJavaProjects())
						.filter(project -> {
							try {
								return Arrays.stream(project.getRawClasspath())
										.anyMatch(entry -> entry.getEntryKind() == IClasspathEntry.CPE_PROJECT);
							} catch (org.eclipse.jdt.core.JavaModelException exception) {
								return false;
							}
						})
						.findFirst()
						.orElseThrow();
		IClasspathEntry[] original = javaProject.getRawClasspath();
		int index =
				java.util.stream.IntStream.range(0, original.length)
						.filter(value -> original[value].getEntryKind() == IClasspathEntry.CPE_PROJECT)
						.findFirst()
						.orElseThrow();
		Map<String, Object> first = GraphSnapshotCommand.execute(monitor);
		IClasspathEntry projectEntry = original[index];
		IClasspathEntry[] changed = Arrays.copyOf(original, original.length);
		changed[index] =
				JavaCore.newProjectEntry(
						projectEntry.getPath(),
						projectEntry.getAccessRules(),
						!projectEntry.combineAccessRules(),
						projectEntry.getExtraAttributes(),
						projectEntry.isExported());
		javaProject.setRawClasspath(changed, monitor);
		Map<String, Object> second = GraphSnapshotCommand.execute(monitor);
		assertNotEquals(first.get("universe"), second.get("universe"));
	}

	private static IClasspathEntry libraryEntry(
			java.nio.file.Path library, String pattern, int kind, boolean ignoreIfBetter) {
		return JavaCore.newLibraryEntry(
				new org.eclipse.core.runtime.Path(library.toString()),
				null,
				null,
				new IAccessRule[] {
					JavaCore.newAccessRule(
							new org.eclipse.core.runtime.Path(pattern),
							kind | (ignoreIfBetter ? IAccessRule.IGNORE_IF_BETTER : 0))
				},
				new org.eclipse.jdt.core.IClasspathAttribute[0],
				false);
	}

	@SuppressWarnings("unchecked")
	private static List<String> rowsOfStrings(Map<String, Object> row, String key) {
		return (List<String>) row.get(key);
	}

	private static Object sourceDigest(Map<String, Object> snapshot, IFile file) {
		return rows(snapshot, "sources").stream()
				.filter(row -> String.valueOf(row.get("uri")).equals(file.getLocationURI().toString()))
				.findFirst().orElseThrow().get("checkerDigest");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> rows(Map<String, Object> snapshot, String key) {
		return (List<Map<String, Object>>) snapshot.get(key);
	}
}
