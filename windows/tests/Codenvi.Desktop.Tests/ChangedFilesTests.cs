using Codenvi.Contracts;
using Codenvi.Desktop.Git;
using Codenvi.Desktop.Services;
using Xunit;

namespace Codenvi.Desktop.Tests;

public class ChangedFilesTests
{
    [Fact]
    public void Maps_new_updated_deleted_and_handles_odd_paths()
    {
        var z = "?? new file.txt\0 M src/a.cs\0M  b.cs\0 D gone.txt\0D  staged-gone.txt\0A  added.txt\0MD both.txt\0?? ünï/ç.txt\0";
        var r = GitService.ParseChangedFiles(z);
        Assert.Equal(new[]
        {
            new FileChange("new file.txt", 'N'), new FileChange("src/a.cs", 'U'), new FileChange("b.cs", 'U'),
            new FileChange("gone.txt", 'D'), new FileChange("staged-gone.txt", 'D'), new FileChange("added.txt", 'N'),
            new FileChange("both.txt", 'D'), new FileChange("ünï/ç.txt", 'N'),
        }, r);
    }

    [Fact]
    public void Rename_is_new_plus_old_deleted_and_copy_is_new_only()
    {
        var r = GitService.ParseChangedFiles("R  new name.txt\0old name.txt\0C  copy.txt\0orig.txt\0 M after.txt\0");
        Assert.Equal(new[]
        {
            new FileChange("new name.txt", 'N'), new FileChange("old name.txt", 'D'),
            new FileChange("copy.txt", 'N'), new FileChange("after.txt", 'U'),
        }, r);
    }

    [Fact]
    public void Empty_output_gives_no_files() => Assert.Empty(GitService.ParseChangedFiles(""));

    [Fact]
    public void Answer_text_is_capped_with_a_more_line()
    {
        var max = ProjectService.MaxChangedFiles;
        var files = Enumerable.Range(0, max + 5).Select(i => new FileChange($"f{i}.txt", 'N')).ToList();
        var lines = ProjectService.FormatChanges(files).Split('\n', StringSplitOptions.RemoveEmptyEntries);
        Assert.Equal(max + 1, lines.Length);
        Assert.Equal("+\t5", lines[^1]);
        Assert.Equal("N\tf0.txt", lines[0]);
    }

    [Fact]
    public void Command_name_is_stable() => Assert.Equal("project.changes", ControlCommands.Changes);
}
