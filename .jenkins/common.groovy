// This file is for internal AMD use.
// If you are interested in running your own Jenkins, please raise a github issue for assistance.

def runCompileCommand(platform, project, jobName, boolean debug=false, boolean buildStatic=false)
{
    project.paths.construct_build_prefix()

    def getDependenciesCommand = ""
    if (project.installLibraryDependenciesFromCI)
    {
        project.libraryDependencies.each
        { libraryName ->
            getDependenciesCommand += auxiliary.getLibrary(libraryName, platform.jenkinsLabel, null, false)
        }
    }

    String clientArgs = '-DBUILD_CLIENTS_SAMPLES=ON -DBUILD_CLIENTS_TESTS=ON -DBUILD_CLIENTS_BENCH=ON'
    String warningArgs = '-DWERROR=ON'
    String buildTunerArgs = '-DROCFFT_BUILD_OFFLINE_TUNER=ON'
    String buildTypeArg = debug ? '-DCMAKE_BUILD_TYPE=Debug -DROCFFT_DEVICE_FORCE_RELEASE=ON' : '-DCMAKE_BUILD_TYPE=Release'
    String buildTypeDir = debug ? 'debug' : 'release'
    String staticArg = buildStatic ? '-DBUILD_SHARED_LIBS=off' : ''
    String cmake = platform.jenkinsLabel.contains('centos') ? 'cmake3' : 'cmake'
    //Set CI node's gfx arch as target if PR, otherwise use default targets of the library
    String amdgpuTargets = env.BRANCH_NAME.startsWith('PR-') ? '-DAMDGPU_TARGETS=\$gfx_arch' : ''
    String rtcBuildCache = "-DROCFFT_BUILD_KERNEL_CACHE_PATH=\$JENKINS_HOME_LOCAL/rocfft_build_cache.db"

    def command = """#!/usr/bin/env bash
                set -x
                cd ${project.paths.project_build_prefix}
                ${getDependenciesCommand}
                set -e
                mkdir -p build/${buildTypeDir} && cd build/${buildTypeDir}
                ${auxiliary.gfxTargetParser()}
                ${cmake} -DCMAKE_CXX_COMPILER=/opt/rocm/bin/hipcc -DCMAKE_C_COMPILER=/opt/rocm/bin/hipcc ${buildTypeArg} ${clientArgs} ${warningArgs} ${buildTunerArgs} ${staticArg} ${amdgpuTargets} ${rtcBuildCache} ../..
                make -j\$(nproc)
                sudo make install
            """
    platform.runCommand(this, command)
}


def runCompileClientCommand(platform, project, jobName, boolean debug=false)
{
    project.paths.construct_build_prefix()

    String clientArgs = '-DBUILD_CLIENTS_SAMPLES=ON -DBUILD_CLIENTS_TESTS=ON -DBUILD_CLIENTS_BENCH=ON'
    String warningArgs = '-DWERROR=ON'
    String cmake = platform.jenkinsLabel.contains('centos') ? 'cmake3' : 'cmake'
    String amdgpuTargets = env.BRANCH_NAME.startsWith('PR-') ? '-DAMDGPU_TARGETS=\$gfx_arch' : ''
    String buildTypeArgClients = debug ? '-DCMAKE_BUILD_TYPE=Debug' : '-DCMAKE_BUILD_TYPE=Release'
    String cmakePrefixPathArg = "-DCMAKE_PREFIX_PATH=${project.paths.project_build_prefix}"

    def command = """#!/usr/bin/env bash
                set -ex
                cd ${project.paths.project_build_prefix}/clients
                mkdir -p build && cd build
                ${cmake} -DCMAKE_CXX_COMPILER=/opt/rocm/bin/hipcc -DCMAKE_C_COMPILER=/opt/rocm/bin/hipcc ${buildTypeArgClients} ${clientArgs} ${warningArgs} ${cmakePrefixPathArg} ${amdgpuTargets} ../
                make -j\$(nproc)
            """
    platform.runCommand(this, command)
}

def runTestCommand (platform, project, boolean debug=false, gfilter='')
{
    String testBinaryName = 'rocfft-test'
    String directory = debug ? 'debug' : 'release'

    String gfilterArg = ''
    if (gfilter)
    {
        gfilterArg = "--gtest_filter=${gfilter}"
    }

    def command = """#!/usr/bin/env bash
                set -ex
                cd ${project.paths.project_build_prefix}/build/${directory}/clients/staging
                ROCM_PATH=/opt/rocm GTEST_LISTENER=NO_PASS_LINE_IN_LOG ./${testBinaryName} --precompile=rocfft-test-precompile.db ${gfilterArg} --gtest_color=yes --R 80 --nrand 10
            """
    platform.runCommand(this, command)
}

def runPackageCommand(platform, project, jobName, boolean debug=false)
{
    String directory = debug ? 'debug' : 'release'
    def packageHelper = platform.makePackage(platform.jenkinsLabel,"${project.paths.project_build_prefix}/build/${directory}",false)
    platform.runCommand(this, packageHelper[0])
    platform.archiveArtifacts(this, packageHelper[1])

    //trim temp files
    def command = """#!/usr/bin/env bash
                     set -ex
                     cd ${project.paths.project_build_prefix}/build/${directory}/
                     rm -rf _CPack_Packages/
                     find -name '*.o' -delete
                  """
    platform.runCommand(this, command)
}

def runSubsetBuildCommand(platform, project, jobName, genPattern, genSmall, genLarge, boolean onlyDouble)
{
    project.paths.construct_build_prefix()

    // Don't build clients, since we're just testing if the library can build
    String clientArgs = ''
    String warningArgs = '-DWERROR=ON'
    String buildTypeArg = '-DCMAKE_BUILD_TYPE=Release'
    String buildTypeDir = 'release'

    String genPatternArgs = "-DGENERATOR_PATTERN=${genPattern}"
    String manualSmallArgs = (genSmall != null) ? "-DGENERATOR_MANUAL_SMALL_SIZE=${genSmall}" : ''
    String manualLargeArgs = (genLarge != null) ? "-DGENERATOR_MANUAL_LARGE_SIZE=${genLarge}" : ''
    String precisionArgs = onlyDouble ? '-DGENERATOR_PRECISION=double' : ''
    String kernelArgs = "${genPatternArgs} ${manualSmallArgs} ${manualLargeArgs} ${precisionArgs}"

    String cmake = platform.jenkinsLabel.contains('centos') ? 'cmake3' : 'cmake'
    //Set CI node's gfx arch as target if PR, otherwise use default targets of the library
    String amdgpuTargets = env.BRANCH_NAME.startsWith('PR-') ? '-DAMDGPU_TARGETS=\$gfx_arch' : ''
    String rtcBuildCache = "-DROCFFT_BUILD_KERNEL_CACHE_PATH=\$JENKINS_HOME_LOCAL/rocfft_build_cache.db"

    def command = """#!/usr/bin/env bash
                set -ex

                cd ${project.paths.project_build_prefix}
                rm -rf build/${buildTypeDir}
                mkdir -p build/${buildTypeDir} && cd build/${buildTypeDir}
                ${auxiliary.gfxTargetParser()}
                ${cmake} -DCMAKE_CXX_COMPILER=/opt/rocm/bin/hipcc -DCMAKE_C_COMPILER=/opt/rocm/bin/hipcc ${buildTypeArg} ${clientArgs} ${kernelArgs} ${warningArgs} ${amdgpuTargets} ${rtcBuildCache} ../..
                make -j\$(nproc)
            """
    platform.runCommand(this, command)
}
return this
