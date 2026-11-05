package com.linbit.linstor.layer.drbd.utils;

import com.linbit.linstor.annotation.Nullable;
import com.linbit.linstor.core.LinStor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A drbdadm config file that includes the global section and only the given resource files.
 * <p>
 * Without "-c", drbdadm parses /etc/drbd.conf, which includes every resource file in
 * {@value LinStor#CONFIG_PATH}. With thousands of resources that parse dominates each "drbdadm adjust" and
 * "drbdadm sh-list-adjustable". Passing this file with "-c" limits the parse to the resources of the call.
 * "-c" alone would also drop global_common.conf, so it is included here explicitly.
 * </p>
 * <p>
 * That is only equivalent to drbd.conf on the standard layout (see {@link #isStandardLayout(Path, Path)}). Callers
 * check it first and otherwise keep the unscoped call.
 * </p>
 * The file is deleted on {@link #close()}.
 */
public final class DrbdAdmScopedConf implements AutoCloseable
{
    static final Path GLOBAL_COMMON_CONF = Paths.get("/etc/drbd.d/global_common.conf");
    static final Path SCOPED_CONF_DIR = Paths.get(LinStor.CONFIG_PATH, "drbdadm-scoped");
    static final Path DRBD_CONF = Paths.get("/etc/drbd.conf");
    static final Path DRBD_CONF_DIR = Paths.get("/etc/drbd.d");
    private static final String RES_FILE_SUFFIX = ".res";
    private static final String SCOPED_CONF_PREFIX = "scope-";
    private static final String SCOPED_CONF_SUFFIX = ".conf";
    private static final String LINSTOR_INCLUDE_FILE = "linstor-resources.res";
    private static final String GLOBAL_COMMON_FILE = "global_common.conf";
    private static final Set<String> STANDARD_DRBD_CONF_INCLUDES = new HashSet<>(
        Arrays.asList(
            "include \"drbd.d/global_common.conf\";",
            "include \"drbd.d/*.res\";",
            "include \"/etc/drbd.d/global_common.conf\";",
            "include \"/etc/drbd.d/*.res\";"
        )
    );

    private final Path confFile;

    private DrbdAdmScopedConf(Path confFileRef)
    {
        confFile = confFileRef;
    }

    /**
     * Returns the resource file drbdadm reads for the given (suffixed) DRBD resource name.
     */
    public static Path resFile(String suffixedRscName)
    {
        return Paths.get(LinStor.CONFIG_PATH, suffixedRscName + RES_FILE_SUFFIX);
    }

    /**
     * Whether a config of global_common.conf plus LINSTOR's resource files reads the same as drbd.conf.
     * <p>
     * That holds if drbd.conf only includes drbd.d/global_common.conf and drbd.d/*.res (the drbd-utils default), and
     * the only *.res file in drbd.d is LINSTOR's include of its resource files. Anything else, such as a common
     * section directly in drbd.conf or another resource file in drbd.d that could carry one, is not known to be
     * equivalent.
     * </p>
     */
    public static boolean isStandardLayout()
    {
        return isStandardLayout(DRBD_CONF, DRBD_CONF_DIR);
    }

    static boolean isStandardLayout(Path drbdConf, Path drbdConfDir)
    {
        boolean standard;
        try
        {
            String content = new String(Files.readAllBytes(drbdConf), StandardCharsets.UTF_8);
            Set<String> statements = new HashSet<>();
            for (String line : content.lines().toList())
            {
                int commentIdx = line.indexOf('#');
                String stmt = (commentIdx >= 0 ? line.substring(0, commentIdx) : line).trim().replaceAll("\\s+", " ");
                if (!stmt.isEmpty())
                {
                    statements.add(stmt);
                }
            }
            standard = statements.size() == 2 && STANDARD_DRBD_CONF_INCLUDES.containsAll(statements) &&
                statements.stream().anyMatch(stmt -> stmt.contains(GLOBAL_COMMON_FILE)) &&
                statements.stream().anyMatch(stmt -> stmt.contains("*" + RES_FILE_SUFFIX));
            if (standard)
            {
                try (Stream<Path> files = Files.list(drbdConfDir))
                {
                    standard = files
                        .map(file -> file.getFileName().toString())
                        .filter(name -> name.endsWith(RES_FILE_SUFFIX))
                        .allMatch(LINSTOR_INCLUDE_FILE::equals);
                }
            }
        }
        catch (IOException exc)
        {
            standard = false;
        }
        return standard;
    }

    /**
     * Deletes config files that a satellite which died during a drbdadm call left behind.
     */
    public static void deleteLeftovers()
    {
        if (Files.isDirectory(SCOPED_CONF_DIR))
        {
            try (Stream<Path> files = Files.list(SCOPED_CONF_DIR))
            {
                files
                    .filter(file -> isScopedConfName(file.getFileName().toString()))
                    .forEach(DrbdAdmScopedConf::deleteQuietly);
            }
            catch (IOException ignored)
            {
                // nothing includes that directory, so leftovers are only clutter
            }
        }
    }

    static boolean isScopedConfName(String fileName)
    {
        return fileName.startsWith(SCOPED_CONF_PREFIX) && fileName.endsWith(SCOPED_CONF_SUFFIX);
    }

    private static void deleteQuietly(Path file)
    {
        try
        {
            Files.deleteIfExists(file);
        }
        catch (IOException ignored)
        {
            // see deleteLeftovers
        }
    }

    public static DrbdAdmScopedConf create(Collection<Path> resFiles) throws IOException
    {
        return create(SCOPED_CONF_DIR, GLOBAL_COMMON_CONF, resFiles);
    }

    static DrbdAdmScopedConf create(Path dir, Path globalCommonConf, Collection<Path> resFiles) throws IOException
    {
        Files.createDirectories(dir);
        Path file = Files.createTempFile(dir, SCOPED_CONF_PREFIX, SCOPED_CONF_SUFFIX);
        try
        {
            Files.write(
                file,
                buildContent(Files.exists(globalCommonConf) ? globalCommonConf : null, resFiles)
                    .getBytes(StandardCharsets.UTF_8)
            );
        }
        catch (IOException exc)
        {
            Files.deleteIfExists(file);
            throw exc;
        }
        return new DrbdAdmScopedConf(file);
    }

    static String buildContent(@Nullable Path globalCommonConf, Collection<Path> resFiles)
    {
        StringBuilder sb = new StringBuilder("# generated by LINSTOR for one drbdadm call, deleted afterwards\n");
        if (globalCommonConf != null)
        {
            appendInclude(sb, globalCommonConf);
        }
        for (Path resFile : resFiles)
        {
            appendInclude(sb, resFile);
        }
        return sb.toString();
    }

    private static void appendInclude(StringBuilder sb, Path path)
    {
        sb.append("include \"").append(path.toAbsolutePath()).append("\";\n");
    }

    public Path getPath()
    {
        return confFile;
    }

    @Override
    public void close()
    {
        deleteQuietly(confFile);
    }
}
