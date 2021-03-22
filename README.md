# SmallRye.io Website Based on Jekyll

## Getting Started

These instructions will get you a copy of the SmallRye.io website up and running on your local machine for development and testing purposes.

### Installation
[Jekyll static site generator docs](https://jekyllrb.com/docs/).

1. Install a full [Ruby development environment](https://jekyllrb.com/docs/installation/)
2. Install Jekyll and [bundler](https://jekyllrb.com/docs/ruby-101/#bundler)  [gems](https://jekyllrb.com/docs/ruby-101/#gems) 
  
        gem install jekyll bundler

3. Fork the [project repository](https://github.com/smallrye/smallrye.github.io), then clone your fork.
  
        git clone git@github.com:YOUR_USER_NAME/smallrye.github.io.git

4. Change into the project directory:
  
        cd smallrye.github.io

5. Use bundler to fetch all required gems in their respective versions

        bundle install

6. Build the site and make it available on a local server
  
        bundle exec jekyll serve
        
7. Now browse to http://localhost:4000

> If you encounter any unexpected errors during the above, please refer to the
[troubleshooting](https://jekyllrb.com/docs/troubleshooting/#configuration-problems) page
or the [requirements](https://jekyllrb.com/docs/installation/#requirements) page,
as you might be missing development headers or other prerequisites.

**For more regarding the use of Jekyll, please refer to the [Jekyll Step by Step Tutorial](https://jekyllrb.com/docs/step-by-step/01-setup/).**

### Building the documentation

Documentation of SmallRye projects is built separately, because it's not using Jekyll. Instead, it's using Antora.

1. Install Antora. See [Antora installation](https://docs.antora.org/antora/2.0/install/install-antora/).

2. Go to the `docs` directory and run

	antora generate antora-playbook.yml --fetch

3. Now you should have the html files for SmallRye documentation in the `docs/build/site` directory. Make sure you're viewing these files directly rather than through Jekyll - explanation is in the following paragraph.

There is a caveat in using Antora together with GitHub Pages, because GitHub Pages runs all files in the repository through Jekyll, which breaks Antora's directory structure. To prevent this, before committing the documentation site to a GitHub Pages repository, create an empty file named `.nojekyll` in the root of the repository. See [Antora documentation](https://docs.antora.org/antora/2.2/run-antora/#publish-to-github-pages) for more information.

## Contributing

Please refer to our Wiki for the [Contribution Guidelines](https://github.com/smallrye/smallrye/).

## License

This website is licensed under the [Creative Commons Attribution 3.0](https://creativecommons.org/licenses/by/3.0/).
