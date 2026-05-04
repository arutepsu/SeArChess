export interface SelectPromptChoice<T> {
  name: string;
  value: T;
  description?: string;
}

export interface SelectPromptOptions<T> {
  message: string;
  choices: SelectPromptChoice<T>[];
  default?: T;
}

export interface InputPromptOptions {
  message: string;
  default?: string;
}

interface InquirerPrompts {
  select<T>(config: SelectPromptOptions<T>): Promise<T>;
  input(config: InputPromptOptions): Promise<string>;
}

const importPrompts = new Function('return import("@inquirer/prompts")') as () => Promise<InquirerPrompts>;

export async function selectPrompt<T>(options: SelectPromptOptions<T>): Promise<T> {
  const prompts = await importPrompts();
  return prompts.select(options);
}

export async function inputPrompt(options: InputPromptOptions): Promise<string> {
  const prompts = await importPrompts();
  return prompts.input(options);
}
